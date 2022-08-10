import constants.Constants;
import dto.ExperimentConfiguration;
import dto.Rule;
import dto.Variation;
import helpers.*;
import exception.*;
import org.ehcache.Cache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class EppoClient {
    /**
     * Static Instance
     */
    private static EppoClient instance = null;
    private ConfigurationStore configurationStore;

    private Poller poller;

    private EppoClient(ConfigurationStore configurationStore, Poller poller) {
        this.configurationStore = configurationStore;
        this.poller = poller;
        poller.run();
    }

    public String getAssignment(String subjectKey, String experimentKey, Map<String, String> subjectAttributes) throws Exception {
        InputValidator.validateNotBlank(subjectKey, "Invalid argument: subjectKey cannot be blank");
        InputValidator.validateNotBlank(experimentKey, "Invalid argument: experimentKey cannot be blank");
        ExperimentConfiguration configuration = this.configurationStore.getExperimentConfiguration(experimentKey);

        String subjectVariationOverride = this.getSubjectVariationOverride(subjectKey, configuration);
        System.out.println(subjectVariationOverride);
        if (subjectVariationOverride != null) {
            return subjectVariationOverride;
        }

        if (!configuration.enabled ||
                !this.isInExperimentSample(subjectKey, experimentKey, configuration) ||
                !this.subjectAttributesSatisfyRules(subjectAttributes, configuration.rules)
        ) {
            return null;
        }
        Variation assignedVariation = this.getAssignedVariation(subjectKey, experimentKey, configuration);
        return assignedVariation.name;
    }

    public String getAssignment(String subjectKey, String experimentKey) throws Exception {
        return this.getAssignment(subjectKey, experimentKey, new HashMap<>());
    }

    private boolean isInExperimentSample(
            String subjectKey,
            String experimentKey,
            ExperimentConfiguration experimentConfiguration
            ) throws Exception
    {
        int subjectShards = experimentConfiguration.subjectShards;
        float percentageExposure = experimentConfiguration.percentExposure;

        int shard = Shard.getShard("exposure-"+subjectKey+"-"+ experimentKey, subjectShards);
        return shard <= percentageExposure * subjectShards;
    }

    private Variation getAssignedVariation(String subjectKey, String experimentKey, ExperimentConfiguration experimentConfiguration) throws Exception {
        int subjectShards = experimentConfiguration.subjectShards;
        int shard = Shard.getShard("assignment-"+subjectKey+"-"+ experimentKey, subjectShards);
        System.out.println(shard);
        Optional<Variation> variation = experimentConfiguration.variations.stream().filter(config -> Shard.isShardInRange(shard, config.shardRange)).findFirst();
        return variation.get();
    }

    private String getSubjectVariationOverride(
            String subjectKey,
            ExperimentConfiguration experimentConfiguration
    ) {
        return experimentConfiguration.overrides.getOrDefault(subjectKey, null);
    }

    private boolean subjectAttributesSatisfyRules(Map<String, String> subjectAttributes, List<Rule> rules) throws Exception {
        if (rules.size() == 0) {
            return true;
        }
        return RuleValidator.matchesAnyRule(subjectAttributes, rules);
    }

    /***
     * This function is used to initialize the Eppo Client with default Base URL
     * @param apiKey
     * @return
     */
    public static EppoClient init(String apiKey) {
        return EppoClient.init(apiKey, Constants.DEFAULT_BASE_URL);
    }

    /***
     * This function is used to initialize the Eppo Client
     * @param apiKey
     * @param baseUrl
     * @return
     */
    public static EppoClient init(String apiKey, String baseUrl) {
        // Create eppo http client
        // @to-do: read sdkName and sdkVersion from pom.xml file
        EppoHttpClient eppoHttpClient = new EppoHttpClient(
                apiKey,
                "node-server-adk",
                "1.0.0",
                baseUrl,
                Constants.REQUEST_TIMEOUT_MILLIS
        );

        // Create wrapper for fetching experiment configuration
        ExperimentConfigurationRequestor expConfigRequestor = new ExperimentConfigurationRequestor(eppoHttpClient);
        // Create Caching for Experiment Configuration
        CacheHelper cacheHelper = new CacheHelper();
        Cache<String, ExperimentConfiguration> experimentConfigurationCache = cacheHelper
                .createExperimentConfigurationCache(Constants.MAX_CACHE_ENTRIES);
        // Create ExperimentConfiguration Store
        ConfigurationStore configurationStore = ConfigurationStore.init(
                experimentConfigurationCache,
                expConfigRequestor
        );

        // Create Poller
        Poller poller = Poller.init(
                () -> {
                    try {
                        if (configurationStore.isFetchingExperimentConfigurationAllowed()) {
                            configurationStore.fetchAndSetExperimentConfiguration();
                            return true;
                        }
                    } catch (Exception e) {}
                    return false;
                },
                Constants.TIME_INTERVAL_IN_MILLIS,
                Constants.JITTER_INTERVAL_IN_MILLIS
        );

        // Create Eppo Client
        EppoClient eppoClient = new EppoClient(configurationStore, poller);
        EppoClient.instance = eppoClient;

        return eppoClient;
    }

    public  static EppoClient getInstance() throws EppoClientIsNotInitializedException {
        if (EppoClient.instance == null) {
            throw new EppoClientIsNotInitializedException("Eppo client is not initialized!");
        }

        return EppoClient.instance;
    }
}
