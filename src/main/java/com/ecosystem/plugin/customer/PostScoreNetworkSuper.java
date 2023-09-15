package com.ecosystem.plugin.customer;

import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

import static com.ecosystem.worker.rest.RestAccess.getRestGeneric;

public class PostScoreNetworkSuper extends PostScoreSuper {

    private static final Logger LOGGER = LogManager.getLogger(PostScoreNetworkSuper.class.getName());

    public PostScoreNetworkSuper() {
    }

    protected static JSONArray handlePreloadCorpora(JSONObject params, JSONObject featuresObj) {
        JSONArray sortJsonArray = new JSONArray();

        if (params.has("preloadCorpora")) {
            if (params.getJSONObject("preloadCorpora").has("network")) {
                try {
                    JSONObject api_params = getAPIParams(params);
                    JSONObject networkCorpora = getNetworkCorpora(params);
                    JSONObject networkCorporaConfig = getNetworkCorporaConfig(params);
                    // JSONObject locations = getLocationsCorpora(params);

                    /**
                     * Get runtime list to call sorted by score
                     */
                    JSONArray runtimeList = new JSONArray();
                    if (networkCorporaConfig.has("type")) {
                        if (networkCorporaConfig.getString("type").equals("model_selector")) {
                            /****************Call Model Selector - Categories******************/
                            runtimeList = callExternal(
                                    params,
                                    api_params,
                                    networkCorporaConfig.getJSONObject("selector")
                            ).getJSONArray("final_result");
                            /** Use model selector out as sorted options for calling other runtimes... */
                            if (runtimeList.isEmpty())
                                LOGGER.error("\nhandlePreloadCorpora:SelectorModel:runtimeList is empty: " + params.toString() + "\n" + api_params.toString() + "\n" + networkCorporaConfig.toString() + "\n");
                            else
                                LOGGER.debug("handlePreloadCorpora:SelectorModel");

                            /**
                             * Call all other runtimes in sequence based on model_selector score sorted...
                             */
                            for (int i = 0; i < runtimeList.length(); i++) {
                                String option = runtimeList.getJSONObject(i).getJSONObject("result").getString("offer"); // category
                                /** Skip if network config does not have the category */
                                if (!networkCorpora.has(option)) continue;

                                /** Skip if in_params ask to exclude the category */
                                if (params.getJSONObject("in_params").has("exclude_category"))
                                    if ((params.getJSONObject("in_params").getString("exclude_category")).contains(option))
                                        continue;

                                JSONObject network = networkCorpora.getJSONObject(option);

                                /****************Call Category Options - Recommender per Category******************/
                                JSONObject resultOffer = callExternal(params, api_params, network);
                                if (resultOffer.isEmpty())
                                    LOGGER.error("ReturningNoResult:Option: " + option + " --> " + resultOffer.toString());

                                if (resultOffer.has("final_result")) {
                                    for (int j = 0; j < resultOffer.getJSONArray("final_result").length(); j++) {
                                        sortJsonArray.put(
                                                resultOffer
                                                        .getJSONArray("final_result")
                                                        .getJSONObject(j)
                                                        .getJSONObject("result")
                                                        .put("offer_name", option)
                                                        .put("selector",  runtimeList.getJSONObject(i).getJSONObject("result"))
                                        );
                                    }
                                }

                            }
                        } else if (networkCorporaConfig.getString("type").equals("no_logging_router")) {
                            String key = networkCorporaConfig.getString("switch_key");
                            JSONObject network = new JSONObject();
                            if (!featuresObj.has(key))
                                LOGGER.error("PostScoreNetwork:E001:Key not found in feature store: " + key + " -- Feature Store: " + featuresObj.toString());
                            else
                                network = networkCorpora.getJSONObject(featuresObj.getString(key));

                            JSONObject resultOffer = callExternal(params, api_params, network);
                            if (resultOffer.has("final_result")) {
                                for (int j = 0; j < resultOffer.getJSONArray("final_result").length(); j++) {
                                    JSONObject tempObject = resultOffer.getJSONArray("final_result").getJSONObject(j).getJSONObject("result");
                                    tempObject.put("passover_uuid", resultOffer.getString("uuid"));
                                    sortJsonArray.put(j, tempObject);
                                }
                            }
                        } else {
                            String key = networkCorporaConfig.getString("switch_key");
                            JSONObject network = new JSONObject();
                            if (!featuresObj.has(key))
                                LOGGER.error("PostScoreNetwork:E001:Key not found in feature store: " + key + " -- Feature Store: " + featuresObj.toString());
                            else
                                network = networkCorpora.getJSONObject(featuresObj.getString(key));

                            JSONObject resultOffer = callExternal(params, api_params, network);
                            if (resultOffer.has("final_result")) {
                                for (int j = 0; j < resultOffer.getJSONArray("final_result").length(); j++) {
                                    sortJsonArray.put(j, resultOffer.getJSONArray("final_result").getJSONObject(j).getJSONObject("result"));
                                }
                            }
                        }
                    } else {
                        LOGGER.error("PostScoreNetwork:E002:No type assigned to config.");
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return sortJsonArray;
    }

    public static JSONObject getAPIParams(JSONObject params) {
        return params.optJSONObject("api_params");
    }

    /**
     * Optional static corpus: locations
     * @param params
     * @return
     */
    public static JSONObject getLocationsCorpora(JSONObject params) {
        return params
                .getJSONObject("preloadCorpora")
                .optJSONObject("locations");
    }

    public static JSONObject getNetworkCorpora(JSONObject params) {
        return params
                .getJSONObject("preloadCorpora")
                .getJSONObject("network");
    }

    public static JSONObject getNetworkCorporaConfig(JSONObject params) {
        return params
                .getJSONObject("preloadCorpora")
                .getJSONObject("network_config")
                .getJSONObject("network_config");
    }

    public static JSONObject callExternal(JSONObject params, JSONObject api_params, JSONObject network) {

        /**
         * First check if there are options from the API. These values will always be available.
         */
        String campaign = api_params.getString("name");
        String subcampaign = api_params.getString("subcampaign");
        String customer = String.valueOf(api_params.get("customer"));
        String channel = api_params.getString("channel");
        int numberoffers = api_params.getInt("resultcount");
        String userid = api_params.getString("userid");
        JSONObject in_params = params.getJSONObject("in_params");

        /**
         * If the network defaults are set, use those settings. These values are assigned in config.
         */
        if (network.has("campaign"))
            if (!network.getString("campaign").isEmpty())
                campaign = network.getString("campaign");

        if (network.has("subcampaign"))
            if (!network.getString("campaign").isEmpty())
                subcampaign = network.getString("campaign");

        if (network.has("customer"))
            if (!String.valueOf(network.get("customer")).isEmpty())
                customer = String.valueOf(network.get("customer"));

        if (network.has("channel"))
            if (!network.getString("channel").isEmpty())
                channel = network.getString("channel");

        if (network.has("numberoffers"))
            if (network.getInt("numberoffers") > 0)
                numberoffers = network.getInt("numberoffers");

        if (network.has("userid"))
            if (!network.getString("userid").isEmpty())
                userid = network.getString("userid");

        if (network.has("in_params"))
            if (!network.optJSONObject("in_params").isEmpty())
                in_params = (JSONObject) network.get("in_params");

        JSONObject config = new JSONObject();
        config.put("action", network.getString("url"));
        config.put("campaign", campaign);
        config.put("subcampaign", subcampaign);
        config.put("customer", customer);
        config.put("channel", channel);
        config.put("numberoffers", numberoffers);
        config.put("userid", userid);
        config.put("params", in_params.toString());

        /**
         * config:
         * {
         *     "action": "http://customer.ecosystem.ai:8091",
         *     "campaign": "recommender_dynamic_bayes",
         *     "subcampaign": "recommender_dynamic_bayes",
         *     "customer": "281db655-d667-4671-a715-8402c29d7d11",
         *     "channel": "app",
         *     "numberoffers": 4,
         *     "userid": "ecosystem_network",
         *     "params": {}
         * }
         */
//        NetworkCall networkCall = new NetworkCall();
//        JSONObject resultOffer = networkCall.getPulseResponder(config);
        try {
            LOGGER.info("==> EXTERNAL:\n" + "POST:" + config.getString("action") + "/invocations :PARAM:" + config.toString());
            return getRestGeneric("POST:" + config.getString("action") + "/invocations :PARAM:" + config.toString());
        } catch (KeyStoreException e) {
            throw new RuntimeException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } catch (KeyManagementException e) {
            throw new RuntimeException(e);
        }

//        return resultOffer;
    }

}
