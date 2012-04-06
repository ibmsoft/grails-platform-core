/* Copyright 2011-2012 the original author or authors:
 *
 *    Marc Palmer (marc@grailsrocks.com)
 *    Stéphane Maldini (stephane.maldini@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.plugin.platform.config

import org.springframework.context.ApplicationContextAware
import org.springframework.context.ApplicationContext

import org.slf4j.LoggerFactory

import grails.util.GrailsNameUtils
import grails.util.Environment

import org.grails.plugin.platform.config.PluginConfigurationEntry
import org.grails.plugin.platform.util.ClosureInvokingScript
import org.grails.plugin.platform.util.PluginUtils

import org.codehaus.groovy.grails.plugins.metadata.GrailsPlugin

import org.codehaus.groovy.grails.commons.GrailsDomainClass

/**
 * Bean for declaring and accessing plugin config 
 * 
 * Plugins declare the config they support, the expected types, validators and default values
 * This means they do not have to supply/merge default values into Config. 
 *
 * The values of these settings provided by the user are read from <app>/grails-app/conf/PluginConfig.groovy
 * so that they can be merged in and validated
 *
 * Config priority is in this order:
 * 1. Values supplied by the application in Config.groovy
 * 2. Values supplied in PluginConfig.groovy
 * 3. Default values specified by the declaring plugin
 *
 */
class PluginConfiguration implements ApplicationContextAware {
    
    static PLUGIN_CONFIG_CLASS = "PluginConfig"
    
    final log = LoggerFactory.getLogger(PluginConfiguration)
    
    def grailsApplication
    def pluginManager
    ApplicationContext applicationContext

    def injectedMethods = {
        def self = this

        // Apply pluginConfig to all artefacts that come from plugins
        '*' { clazz, artefact ->
            def pluginName = PluginUtils.getNameOfDefiningPlugin(applicationContext, clazz)
            if (pluginName) {
                def pluginConf = self.getPluginConfig(pluginName)
                
                getPluginConfig(staticMethod:artefact instanceof GrailsDomainClass) { ->
                    pluginConf
                }
            }
        }
        
    }
    
    /**
     * Get pluginConfig for any object, determined by the plugin in which is was defined
     */
    def getPluginConfigFor(objectInstance) {
        def pluginName = PluginUtils.getNameOfDefiningPlugin(applicationContext, objectInstance.getClass())

        if (pluginName) {
            return getPluginConfig(pluginName)
        } else {
            return Collection.EMPTY_MAP
        }
    }
    
    protected List<PluginConfigurationEntry> pluginConfigurationEntries = []

    protected ConfigObject loadPluginConfig() {
        // @todo how to load these from plugins, and to make sure they are included in WAR?
        GroovyClassLoader classLoader = new GroovyClassLoader(PluginConfiguration.classLoader)
		ConfigSlurper slurper = new ConfigSlurper(Environment.getCurrent().getName())
		try {
            log.debug "Loading plugin configuration metadata from ${PLUGIN_CONFIG_CLASS} ..."
			return slurper.parse(classLoader.loadClass(PLUGIN_CONFIG_CLASS))
		}
		catch (ClassNotFoundException e) {
            return new ConfigObject()
		}
    }

    /**
     * Set an app config value using a full string path key
     */
    protected void setConfigValueByPath(String fullPath, value) {
        def config = grailsApplication.config
        
        def parentConfObj
        def path = fullPath.tokenize('.')
        def valueName
        if (path.size() > 1) {
            valueName = path[-1]
            path = path[0..(path.size()-2)]
        } else {
            valueName = path[0]
            path = []
        }

        log.debug "Config path is $path , value name is $valueName"
        
        // Find the last existing element
        path.find { k -> 
            // Find the nearest end point in the config
            def c = config[k]
            if (c instanceof ConfigObject) {
                config = c
                return false
            } else {
                // We should throw here, its an error...
                return true
            }
        }

        config.putAll([(valueName):value])
    }
    
    /**
     * Return the plugin-specific ConfigObject for the given plugin
     * @param pluginName the BEAN notation name of the plugin e.g. beanFields
     */
    ConfigObject getPluginConfig(String pluginName) {
        grailsApplication.config.plugin[pluginName]
    }
    
    /**
     * Take app config, merge in config from PluginConfig.groovy and then doWithConfig blocks,
     * and validate the whole lot according to doWithConfigOptions
     */
    void applyConfig() {
        log.debug "Applying doWithConfig and doWithConfigOptions..."

        // Get the metadata we need
        loadConfigurationOptions()
        
        // Load up user-supplied plugin configs
        applyAppPluginConfiguration(loadPluginConfig())

        // Let plugins merge in their configs if no explicit setting given by user
        mergeDoWithConfig()
        
        // Now validate plugin config
        applyPluginConfigurationDefaultValuesAndConstraints()
        log.debug "After applying doWithConfig and doWithConfigOptions, application config is: ${grailsApplication.config}"
        
        verifyConfig()
    }
    
    /**
     * Warn the user if any plugin.x config exists that is not declared by a plugin
     */
    void verifyConfig() {
        def registeredKeys = pluginConfigurationEntries*.fullConfigKey as Set
        def flatAppConf = grailsApplication.config.flatten()
        // @todo we falsely report Map values as invalid config currently as flatten() flattens these too
        flatAppConf.each { k, v ->
            if (k.startsWith('plugin.')) {
                if (!registeredKeys.contains(k)) {
                    // @todo should we fail fast here?
                    log.warn "Your configuration contains a value for [${k}] which is not declared by any plugin"
                }
            }
        }
    }
    
    /**
     * Take a Closure and use it as config, returns a ConfigObject
     */
    ConfigObject parseConfigClosure(Closure c) {
        new ConfigSlurper().parse(new ClosureInvokingScript(c))
    }
        
    /**
     * Load cross-plugin doWithConfig configuration and merge into main app config
     */
    void mergeDoWithConfig() {
        if (log.debugEnabled) {
            log.debug "About to merge plugin configs into main Config which is currently: ${grailsApplication.config}"
        }

        def plugins = pluginManager.allPlugins
        // @todo what order is this - plugin dependency order?
        plugins.each { p ->
            def pluginName = GrailsNameUtils.getLogicalPropertyName(p.pluginClass.name, 'GrailsPlugin')
            def inst = p.instance
            if (inst.metaClass.hasProperty(inst, 'doWithConfig')) {
                def confDSL = inst.doWithConfig.clone()

                if (log.debugEnabled) {
                    log.debug "Getting doWithConfig configuration metadata for plugin ${pluginName}"
                }
                def builder = new ConfigBuilder()
                confDSL.delegate = builder
                confDSL(grailsApplication.config)
                
                def newConf
                
                // Merge in any non-namespaced app config
                if (builder._applicationConfig) {
                    newConf = parseConfigClosure(builder._applicationConfig)
                } else {
                    newConf = new ConfigObject()
                }
                
                if (newConf.size()) {
                    if (log.debugEnabled) {
                        log.debug "Plugin ${pluginName} added application configuration settings: ${newConf}"
                    }
                }
                
                // @todo run these in plugin dependency order? If two plugins set something on another plugin, which one wins?
                builder._pluginConfigs.each { confPluginName, code -> 
                    // @todo add safety check to prevent plugins configuring themselves (creates ordering problems)
                    // @todo verify its a real plugin with exposed config
                    def pluginConf = parseConfigClosure(code)
                    newConf.plugin."$confPluginName" = pluginConf

                    if (pluginConf.size()) {
                        if (log.debugEnabled) {
                            log.debug "Plugin ${pluginName} modified plugin configuration for plugin ${confPluginName}: ${pluginConf}"
                        }
                    }
                }

                // Now merge all this into main config, so that values are only replaced
                // if the application did not already set something explicitly
                if (log.debugEnabled) {
                    log.debug "Plugin ${pluginName} config changes being merged into main config: ${newConf}"
                }
                def merged = newConf.merge(grailsApplication.config) 

                if (log.debugEnabled) {
                    log.debug "Plugin ${pluginName} config changes replacing main config which is: ${grailsApplication.config}"
                }
                grailsApplication.config.putAll( merged)

                if (log.debugEnabled) {
                    log.debug "Plugin ${pluginName} config changes merged into main config resulted in: ${grailsApplication.config}"
                }
            }
        }
    }

    /**
     * Load plugin config settigns from PluginConfig.groovy and merge into main app config
     */
    void applyAppPluginConfiguration(ConfigObject appPluginConfig) {
        log.debug "Applying user-supplied plugin configuration..."
        def conf = grailsApplication.config
        def flatConf = conf.flatten()
        def pluginConf = appPluginConfig
        def pluginConfFlat = pluginConf.flatten()
        def registeredKeys = pluginConfigurationEntries*.fullConfigKey as Set
        
        pluginConfFlat.each { key, pluginConfValue ->
            if (!registeredKeys.contains(key)) {
                log.warn "Skipping plugin configuration entry ${key}, no plugin configuration has been declared for this."
                return
            }
            // 1. see if there is already an entry defined by the app
            def scopedKey = key
            log.debug "Applying plugin configuration entry ${scopedKey}"
            def value = flatConf[scopedKey]
            def newValue 
            def valueChanged = false
            if (value instanceof ConfigObject) {
                log.debug "Applying plugin configuration entry ${scopedKey}, no value defined by application"
                // 2. if not, see if there is one in the generated plugin conf file
                if (!(pluginConfFlat[scopedKey] instanceof ConfigObject)) {
                    // 3. set the plugin config value into the main config
                    newValue = pluginConfValue
                    valueChanged = true
                    log.debug "Using user-supplied value for ${scopedKey}: [$newValue]"
                }
                if (valueChanged) {
                    // set main config value
                    log.debug "Updating application config for ${scopedKey} to [$newValue]"
                    setConfigValueByPath(scopedKey, newValue)
                    value = newValue
                }
            }
        }
    }
    
    /**
     * Apply plugin-supplied config defaults for declared config values if values are missing
     * and then validate
     */
    void applyPluginConfigurationDefaultValuesAndConstraints() {
        log.debug "Applying plugin configuration constraints..."
        def conf = grailsApplication.config
        def flatConf = conf.flatten()
        
        // Temporarily, we will assum they called register() at start of doWithSpring
        pluginConfigurationEntries.each { entry ->
            def scopedKey = entry.fullConfigKey
            log.debug "Applying plugin configuration entry ${scopedKey}"
            def value = flatConf[scopedKey]
            if ((value instanceof ConfigObject) && value.size() == 0) {
                log.debug "Using plugin default value for ${scopedKey}: [${entry.defaultValue}]"
                value = entry.defaultValue
                setConfigValueByPath(scopedKey, value)
            }

            if (entry.type && (value != null) && !(value instanceof ConfigObject)) { 
                if (!entry.type.isAssignableFrom(value.getClass())) {
                    log.error "Invalid plugin configuration value [${value}] for [$scopedKey], reverting to default value [${entry.defaultValue}] - the value in config is not compatible with the type: ${entry.type}"
                    setConfigValueByPath(scopedKey, entry.defaultValue)
                }
            }
            
            // apply validator
            if (entry.validator) {
                log.debug "Appling plugin config validator for ${scopedKey} to [$value]"
                def msg = entry.validator.call(value)
                if (msg != null) {
                    // @todo Do we fail fast? Probably not, we may want interaction
                    log.error "Invalid plugin configuration value for [$scopedKey], reverting to default value [${entry.defaultValue}] - cause: ${msg}"
                    // Revert to default value
                    setConfigValueByPath(scopedKey, entry.defaultValue)
                }
            }
        }
    }
    
    /**
     * Load up all the doWithConfigOptions metadata
     */
    protected void loadConfigurationOptions() {
        log.debug "Loading plugin configuration metadata..."
        def plugins = pluginManager.allPlugins
        plugins.each { p ->
            def inst = p.instance
            if (inst.metaClass.hasProperty(inst, 'doWithConfigOptions')) {
                log.debug "Getting plugin configuration metadata for plugin ${p.name}"
                def builder = new ConfigOptionsBuilder(pluginName:GrailsNameUtils.getLogicalPropertyName(p.pluginClass.name, 'GrailsPlugin'))
                def code = inst.doWithConfigOptions.clone()
                code.delegate = builder
                code()
                
                log.debug "Plugin configuration metadata for plugin ${p.name} yielded entries: ${builder.entries*.fullConfigKey}"
                pluginConfigurationEntries.addAll(builder.entries)
                
                // Load the DSL and add plugin config entries
            }
        }
    }
    
    /**
     * Get information about all the declared plugin config variables
     */
    List<PluginConfigurationEntry> getAllEntries() {
        pluginConfigurationEntries
    }
}

class ConfigOptionsBuilder {
    List<PluginConfigurationEntry> entries = []

    String pluginName
    String prefix
    
    def methodMissing(String name, args) {
        def e = new PluginConfigurationEntry()
        assert args.size() <= 1
        e.plugin = pluginName
        e.prefix = prefix
        e.key = name
        if (args.size()) {
            def params = args[0]
            e.defaultValue = params.defaultValue
            e.type = params.type
            if (params.validator instanceof Closure) {
                e.validator = params.validator
            }
        }
        entries << e
        return null
    }
}


class ConfigBuilder {
    Closure _applicationConfig
    Map<String, Closure> _pluginConfigs = [:]
    
    def methodMissing(String name, args) {
        assert args.size() == 1
        assert args[0] instanceof Closure
        if (name == 'application') {
            assert _applicationConfig == null
            _applicationConfig = args[0]
        } else {
            // @todo add support for multiple closures / fail fast if called again?
            assert _pluginConfigs[name] == null
            _pluginConfigs[name] = args[0]
        }

        return null
    }
}