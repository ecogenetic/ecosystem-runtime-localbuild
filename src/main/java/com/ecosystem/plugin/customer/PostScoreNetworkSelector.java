package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import hex.genmodel.easy.EasyPredictModelWrapper;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 */
public class PostScoreNetworkSelector extends PostScoreNetworkSuper {

    private static final Logger LOGGER = LogManager.getLogger(PostScoreNetworkSelector.class.getName());

    public PostScoreNetworkSelector() {
    }

    /**
     * Pre-post predict logic
     */
    public void getPostPredict () {
    }

    /**
     * getPostPredict
     *
     * @param predictModelMojoResult Result from scoring
     * @param params                 Params carried from input
     * @param session                Session variable for Cassandra
     * @param models                  Preloaded H2O Models
     * @return JSONObject result to further post-scoring logic
     */
    public static JSONObject getPostPredict(JSONObject predictModelMojoResult, JSONObject params, CqlSession session, EasyPredictModelWrapper[] models) {
        double startTimePost = System.nanoTime();
        try {
            /* Setup JSON objects for specific prediction case */
            JSONObject featuresObj = predictModelMojoResult.getJSONObject("featuresObj");

            /** Final offer list based on score */
            JSONArray sortJsonArray = new JSONArray();

            /** Execute network based on settings in corpora */
            /**
             * Configure a network of client pulse responders bu changing configuration based on lookup, scoring and
             * other criteria. Ensure that the lookup settings coordinate and that default have been set or removed.
             * Example, if there's a customer, or other settings in the __network collection, it will use those.
             * If you want customer to go straight through, then remove that default.
             *
             * Additional corpora settings in project:
             * [
             * {name:'network',database:'mongodb',db:'master',table:'bank_full_1__network', type:'static', key:'value' },
             * {name:'network_config',database:'mongodb',db:'master',table:'bank_full_1__network_config', type:'static', key:'name' }
             * ]
             * Add this line to "Additional Corpora" in your project:
             * [{name:'network',database:'mongodb',db:'master',table:'bank_full_1__network', type:'static', key:'value' },{name:'network_config',database:'mongodb',db:'master',table:'bank_full_1__network_config', type:'static', key:'name' }]
             *
             * bank_full_1__network_config, ensure that this document contains this: "name": "network_config":
             * {
             *   "switch_key": "marital",
             *   "name": "network_config"
             *   "type": "model_selector",
             *   "selector": {
             *      "subcampaign": "recommender_dynamic_bayes",
             *      "channel": "app",
             *      "campaign": "recommender_dynamic_bayes",
             *      "params": "{}",
             *      "value": "married",
             *      "userid": "ecosystem_network",
             *      "url": "http://customer.ecosystem.ai:8091",
             *      "customer": "281db655-d667-4671-a715-8402c29d7d11"
             *   }
             * }
             * In the model_selector the switch_key is not used, but offer returned from model score is used.
             *
             * bank_full_1__network, all options will be setup here. Ensure that "value": "" contains a valid value as per switch_key:
             * {
             *   "numberoffers": 4,
             *   "subcampaign": "recommender_dynamic_bayes",
             *   "channel": "app",
             *   "campaign": "recommender_dynamic_bayes",
             *   "params": "{}",
             *   "value": "married",
             *   "userid": "ecosystem_network",
             *   "url": "http://customer.ecosystem.ai:8091",
             *   "customer": "281db655-d667-4671-a715-8402c29d7d11",
             * }
             */
            sortJsonArray = handlePreloadCorpora(params, featuresObj);

            predictModelMojoResult.put("final_result", sortJsonArray);

        } catch (Exception e) {
            LOGGER.error("PostScoreNetwork:E001: " + e);
        }

        /** Get top scores and test for explore/exploit randomization */
        predictModelMojoResult = getTopScores(params, predictModelMojoResult);

        double endTimePost = System.nanoTime();
        LOGGER.info("PostScoreNetwork:I001: execution time in ms: ".concat( String.valueOf((endTimePost - startTimePost) / 1000000) ));
        return predictModelMojoResult;
    }

}
