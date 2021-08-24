/**
* SETUP YOUR REST ENDPOINT DEFINITION.
*
*/

package com.ecosystem.runtime;

import com.ecosystem.EcosystemMaster;
import com.ecosystem.EcosystemResponse;
import com.ecosystem.data.mongodb.ConnectionFactory;
import com.ecosystem.utils.ActivityLog;
import com.ecosystem.utils.GlobalSettings;
import com.ecosystem.utils.JSONDecode;
import com.ecosystem.worker.h2o.ModelMojoWorkerH2O;
import com.ecosystem.worker.h2o.RunModelMojo;
import com.ecosystem.worker.kafka.BasicProducerKerberos;
import com.mongodb.client.MongoCollection;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.springframework.web.bind.annotation.*;

import javax.annotation.security.RolesAllowed;
import java.io.IOException;
import java.text.ParseException;
import java.util.Map;
import java.util.UUID;

import static com.ecosystem.EcosystemMaster.generateUUID;
import static com.ecosystem.data.mongodb.MongoDBWorkerLogging.addLoggingAsync;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.PUT;

@CrossOrigin
@RolesAllowed({"ADMIN", "USER"})
@RestController
@Api(value = "ecosystemAi-predictor", description = "Review model domain details, refresh model parameter loading and perform predictions. " +
    "There are two primary approaches to invoking a prediction for scoring via a model namely; Invoke model and return a JSON response that can be used in any application," +
    " invoke model and deliver result onto a KAFKA topic of your choosing. Model can also be tested by dynamically loading a MOJO, mostly used for testing purposes." +
            "The predictor parameters are broken into two types namely, requiring all parameters via API or requiring a lookup key via API and extracting parameters " +
            "from a data source." +
            "\nUse this format for input prams only:\n" +
            "{'name':'predict1', 'mojo':'model_mojo.zip','dbparam':false,'input': ['x','y'],'value': ['val_x', 'val_y']}" +
            "\nUse this approach for inputs from data source:\n" +
            "{'name':'predict1', 'mojo':'model_mojo.zip','dbparam':true, lookup:{key:'customer',value:1234567890}} " +
            "\nIf there is post-scoring logic, then ise this configuration:\n" +
            "{'name':'predict1', 'mojo':'1','mab':{'class':'mabone', 'epsilon':0.4},'dbparam':true, lookup:{key:'customer',value:1234567890}, param:{key:'value_field', value:30}}\n",
    tags = "PredictorEngine")

public class ProductMaster {

    private static final Logger LOGGER = LogManager.getLogger(ProductMaster.class.getName());

    private GlobalSettings settings;
    private ConnectionFactory settingsConnection;
    private EcosystemMaster ecosystemMaster;
    private EcosystemResponse ecosystemResponse;

    public ProductMaster() throws Exception {
        settings = new GlobalSettings();
        settingsConnection = new ConnectionFactory();
        ecosystemMaster = new EcosystemMaster(settingsConnection, settings);
        ecosystemResponse = new EcosystemResponse(settingsConnection, settings, ecosystemMaster.basicProducerKerberos);
    }

    /**
     * Refresh product matrix and master
     *
     * @return Result
     */
    @ApiOperation(value = "Refresh product matrix and master")
    @RequestMapping(value = "/closeCassandra", method = GET)
    public String closeCassandra(@RequestHeader Map<String, String> headers) throws Exception {
        LOGGER.info("/closeCassandra API");

        if (ecosystemMaster.session != null) {
            try {
                ecosystemMaster.session.forceCloseAsync();
            } catch (Exception e) {
            }
            try {
                ecosystemMaster.session.close();
            } catch (Exception e) {
            }
        }
        return null;
    }


    /**
     * PLEASE DO NOT ALTER THIS
     * Refresh product matrix and master
     *
     * @return Result
     */
    @ApiOperation(value = "Refresh product matrix and master")
    @RequestMapping(value = "/refresh", method = GET)
    public String refresh(@RequestHeader Map<String, String> headers) throws Exception {
        LOGGER.info("/refresh API");

        if (ecosystemMaster.session != null)
            ecosystemMaster.session.close();

        if (settingsConnection.mongoClient != null)
            settingsConnection.mongoClient.close();

        if (settingsConnection.mongoClient2 != null)
            settingsConnection.mongoClient2.close();

        if (ecosystemMaster.preLoadCorpora.settingsConnection.mongoClient != null)
            ecosystemMaster.preLoadCorpora.settingsConnection.mongoClient.close();

        if (ecosystemMaster.settingsConnection.mongoClient != null)
            ecosystemMaster.settingsConnection.mongoClient.close();
        if (ecosystemMaster.settingsConnection.mongoClient2 != null)
            ecosystemMaster.settingsConnection.mongoClient2.close();

        if (ecosystemResponse.preLoadCorpora.settingsConnection.mongoClient != null)
            ecosystemResponse.preLoadCorpora.settingsConnection.mongoClient.close();

        if (ecosystemResponse.settingsConnection.mongoClient != null)
            ecosystemResponse.settingsConnection.mongoClient.close();
        if (ecosystemResponse.settingsConnection.mongoClient2 != null)
            ecosystemResponse.settingsConnection.mongoClient2.close();

        // ProductMaster pM = new ProductMaster();

        this.settings = new GlobalSettings();

        this.settingsConnection = new ConnectionFactory();
        this.ecosystemMaster = new EcosystemMaster(settingsConnection, this.settings);
        this.ecosystemResponse = new EcosystemResponse(settingsConnection, this.settings, this.ecosystemMaster.basicProducerKerberos);

        LOGGER.info("refresh:Models: " + this.settings.getMojo());
        LOGGER.info("refresh:Epsilon: " + this.settings.getEpsilon());
        try {
            return "{'message':'Success'}";
        } catch (NullPointerException e) {
            JSONObject error = new JSONObject();
            error.put("ErrorMessage", "Refresh failed, please check database or other connections, models, etc: " + e.getMessage());
            LOGGER.info("refresh: Reload Failed");
            return error.toString().intern();
        }
    }

    /**************************************************************************************************************/

    /**
     * Score model from pre-loaded mojo as set in the properties file
     ** @return Result
     */
    @ApiOperation(value = " ", response = String.class)
    @RequestMapping(value = "/testKafkaKerberos", method = RequestMethod.GET)
    public String testKafkaKerberos() {
        LOGGER.info("/testKafkaKerberos ");
        try {
            BasicProducerKerberos basicProducerKerberos = new BasicProducerKerberos();
            try {
                String result = basicProducerKerberos.mainTest();
                // basicProducerKerberos.closeKafka();
                return result;
            } catch (Exception e) {
                LOGGER.error("PredictorMaster:testKafkaKerberos:E000: Param error: " + e);
                e.printStackTrace();
                return "{\"ErrorMessage\":\"PredictorMaster:testKafkaKerberos:E000-1: Parameter error.\"" + e + "}";
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ("{\"ErrorMessage\":\"Error: " + e + "\"}");
        }
    }

    /**************************************************************************************************************/

    /**
     * Prediction case is determined from properties file setup: mojo's, feature store, and other settings.
     * <p>
     * Balance enquire Case:
     * From paramsParams - balance enquiry example: {msisdn:0828811817,in_balance:50,voice_balance:12,data_balance:400,n_offers:1}
     * <p>
     * Recharge Recommender Case:
     * {'name':'rechargerecommender', 'mojo':'1','mab':{'class':'mabone', 'epsilon':0.4},'dbparam':true, lookup:{key:'msisdn',value:849999330}, param:{key:'in_recharge', value:100}, resultcount:2}
     *
     * @param campaign     campaign
     * @param subcampaign  subcampaign
     * @param customer     customer
     * @param channel      channel
     * @param numberoffers numberoffers
     * @return Result
     */
    @ApiOperation(value = "Provide offers that form part of a campaign for particular customer.")
    @ApiResponse(code = 200, message = "Recommender successfully completed")
    @RequestMapping(value = "/offerRecommendations", method = GET)
    public String getOfferRecommendations(@RequestHeader Map<String, String> headers,
                                          @RequestParam(value = "campaign", defaultValue = "") String campaign,
                                          @RequestParam(value = "subcampaign", defaultValue = "", required = false) String subcampaign,
                                          @RequestParam(value = "customer", defaultValue = "") String customer,
                                          @RequestParam(value = "channel", defaultValue = "") String channel,
                                          @RequestParam(value = "numberoffers", defaultValue = "", required = false) int numberoffers,
                                          @RequestParam(value = "userid", defaultValue = "") String userid,
                                          @RequestParam(value = "params", defaultValue = "", required = false) String jsonParams) throws Exception {
        LOGGER.info("/offerRecommendations API");

        JSONObject paramsParams = new JSONObject();
        try {
            paramsParams = new JSONObject(JSONDecode.decode(jsonParams));
        } catch (org.json.JSONException e) {
            LOGGER.info("/offerRecommendations malformed params JSON input: " + jsonParams);
            return paramsParams.put("ErrorMessage", e).toString().intern();
        }

        /* Setup values from input params that will be placed in */
        JSONObject param = new JSONObject();
        String uuid = generateUUID();
        param.put("headers", headers);
        param.put("uuid", uuid);
        param.put("name", campaign);
        param.put("subcampaign", subcampaign);
        param.put("channel", channel);
        param.put("subname", subcampaign);
        param.put("resultcount", numberoffers);
        param.put("userid", userid);
        /* this is needed to not cause a stack overflow as adding current value of json object */
        JSONObject inParam = new JSONObject(param.toString());
        param.put("api_params", inParam);
        param = ValidateParams.getLookupFromParams(settings, param, customer);

        /* Set defaults for model and paramneters from database */
        param.put("mojo", "1");
        param.put("dbparam", true);

        /* Obtain default epsilon from properties or obtain from input params */
        if (!paramsParams.has("mab")) {
            JSONObject mabParam = new JSONObject();
            mabParam.put("class", "mabone");
            mabParam.put("epsilon", settings.getEpsilon());
            param.put("mab", mabParam);
        } else {
            param.put("mab", paramsParams.getJSONObject("mab"));
        }
        param.put("in_params", paramsParams);

        JSONObject predictResult = new JSONObject();
        /* Primary prediction from EcosystemMaster.getPredictionResult */
        predictResult = ecosystemMaster.getPredictionResult(param);
        if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
        if (predictResult.has("ErrorMessage")) {
            predictResult.put("error", 1);
        }

        predictResult.remove("predict_result");
        return predictResult.toString().intern();
    }

    /**
     * Update offers taken up by customers/msisdn
     *
     * @param documentJSON documentJSON
     * @return Result
     */
    @ApiOperation(value = "Update offers taken up by customers")
    @RequestMapping(value = "/offerRecommendations", method = PUT)
    public String putOfferRecommendations(@RequestHeader Map<String, String> headers,
                                          @RequestParam(value = "document", defaultValue = "") String documentJSON) throws IOException, ParseException {
        LOGGER.info("/offerRecommendations PUT API");
        String response = ecosystemResponse.putResponse(JSONDecode.decode(documentJSON));
        return response;
    }

    /**************************************************************************************************************/

    /**
     * Prediction case is determined from properties file setup: mojo's, feature store, and other settings.
     * <p>
     * Balance enquire Case:
     * From paramsParams - balance enquiry example: {msisdn:0828811817,in_balance:50,voice_balance:12,data_balance:400,n_offers:1}
     * <p>
     * Recharge Recommender Case:
     * {'name':'layalty_recommender', 'mojo':'1','mab':{'class':'mabone', 'epsilon':0.4},'dbparam':true, lookup:{key:'msisdn',value:849999330}, param:{key:'in_recharge', value:100}, resultcount:2}
     *
     * @param campaign     campaign
     * @param subcampaign  subcampaign
     * @param customer     customer
     * @param channel      channel
     * @param numberoffers numberoffers
     * @return Result
     */
    @ApiOperation(value = "Provide eStore offers that form part of a campaign for particular customer.")
    @ApiResponse(code = 200, message = "Recommender successfully completed")
    @RequestMapping(value = "/estoreRecommendations", method = GET)
    public String estoreRecommendations(  @RequestHeader Map<String, String> headers,
                                          @RequestParam(value = "msisdn", defaultValue = "") String customer,
                                          @RequestParam(value = "payment_method", defaultValue = "") String paymentMethod,
                                          @RequestParam(value = "campaign_id", defaultValue = "") String campaign,
                                          @RequestParam(value = "sub_campaign_id", defaultValue = "", required = false) String subcampaign,
                                          @RequestParam(value = "channel_name", defaultValue = "") String channel,
                                          @RequestParam(value = "number_of_offers", defaultValue = "", required = false) int numberoffers,
                                          @RequestParam(value = "user_id", defaultValue = "") String userid,
                                          @RequestParam(value = "params", defaultValue = "", required = false) String jsonParams) throws Exception {
        LOGGER.info("/estoreRecommendations API");

        JSONObject paramsParams = new JSONObject();
        try {
            paramsParams = new JSONObject(JSONDecode.decode(jsonParams));
        } catch (org.json.JSONException e) {
            LOGGER.info("/estoreRecommendations malformed params JSON input: " + jsonParams);
            return paramsParams.put("ErrorMessage", e).toString().intern();
        }

        JSONObject param = new JSONObject();
        String uuid = generateUUID();
        param.put("headers", headers);
        param.put("uuid", uuid);
        param.put("name", campaign);
        param.put("campaign_id", campaign);
        param.put("subcampaign", subcampaign);
        param.put("channel", channel);
        param.put("subname", subcampaign);
        param.put("resultcount", numberoffers);
        param.put("userid", userid);
        param.put("api_payment_method", paymentMethod);
        /* use api_params key to store values in the params json object to allow for logging */
        JSONObject inParam = new JSONObject(param.toString());
        param.put("api_params", inParam);
        param = ValidateParams.getLookupFromParams(settings, param, customer);

        /* Set defaults for model and paramneters from database */
        param.put("mojo", "1");
        param.put("dbparam", true);

        /* Obtain default epsilon from properties or obtain from input params */
        if (!paramsParams.has("mab")) {
            JSONObject mabParam = new JSONObject();
            mabParam.put("class", "mabone");
            mabParam.put("epsilon", settings.getEpsilon());
            param.put("mab", mabParam);
        } else {
            param.put("mab", paramsParams.getJSONObject("mab"));
        }
        param.put("in_params", paramsParams);

        JSONObject predictResult = new JSONObject();
        /* Primary prediction from EcosystemMaster.getPredictionResult */
        predictResult = ecosystemMaster.getPredictionResult(param);
        if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
        if (predictResult.has("ErrorMessage")) {
            predictResult.put("error", 1);
        }

        /* TODO MAKE THIS CONFIGURABLE IN THE WORKBENCH */
        /* stage specific JSON result */
        JSONObject result = new JSONObject();
        result.put("cache", predictResult.get("cache"));
        result.put("request_date", predictResult.get("datetime"));
        result.put("explore",predictResult.get("explore"));
        result.put("msisdn", customer);
        result.put("campaign_id", campaign);
        result.put("session_id", param.get("uuid"));
        result.put("uuid", param.get("uuid"));
        result.put("in_params", param.get("in_params"));
        result.put("final_result", predictResult.getJSONArray("final_result"));
        if (param.has("payment_method_code"))
            result.put("payment_method",param.get("payment_method_code"));
        else
            result.put("payment_method",paymentMethod);

        return result.toString().intern();
    }

    /**
     * Update eStore offers taken up by customers/msisdn.
     *
     * @param documentJSON documentJSON
     * @return Result
     */
    @ApiOperation(value = "Update eStore offers taken up by customers. Supported response format:" +
            "{\"uuid\":\"dcb54a23-0737-4768-845d-48162598c0f7\",\"offers_accepted\":[{\"offer_treatment_code\":\"GSM_999_A\"}],\"channel_name\":\"USSD\",\"transaction_id\":\"uuid:0aa9140a-755e-48de-84a2-0a67451804f7\"}")
    @RequestMapping(value = "/estoreRecommendations", method = PUT)
    public String estoreRecommendations(@RequestHeader Map<String, String> headers,
                                        @RequestParam(value = "document", defaultValue = "") String documentJSON) throws IOException, ParseException {
        LOGGER.info("/estoreRecommendations PUT API");
        String response = ecosystemResponse.putResponse(JSONDecode.decode(documentJSON));
        if (response == null)
            LOGGER.error("/estoreRecommendations PUT API input document: " + documentJSON);
         else
            LOGGER.info("/estoreRecommendations PUT API response: " + response);
        return response;
    }

    /**************************************************************************************************************/

    /**
     * TODO - CREATING A NEW PREDICTION CASE:
     * TODO - 1. SETUP ENDPOINT WITH INPUT PARAMS
     * TODO - 2. AND RESULT PAYLOAD
     * TODO - 3. CREATE POST SCORE CLASS
     * TODO - 4. SETUP ALL PRODUCT OPTIONS IN PROPERTIES
     * TODO - 5. REVIEW WHITE LIST COLLECTION
     * TODO - 6. REVIEW FEATURE STORE
     * TODO - 7. REVIEW OFFER MATRIX
     */
    private String getRecommendations(String customer,
                                      String paymentMethod,
                                      String campaign,
                                      String subcampaign,
                                      String channel,
                                      int numberoffers,
                                      String userid,
                                      String jsonParams,
                                      String transactionId
    ) throws Exception {
        double startTime = System.nanoTime();
        JSONObject paramsParams = new JSONObject();
        try {
            paramsParams = new JSONObject(JSONDecode.decode(jsonParams));
        } catch (org.json.JSONException e) {
            LOGGER.info("/getRecommendations malformed params JSON input: " + jsonParams);
            return paramsParams.put("ErrorMessage", e).toString().intern();
        }

        JSONObject param = new JSONObject();
        String uuid = generateUUID();
        param.put("uuid", uuid);
        param.put("name", campaign);
        param.put("campaign_id", campaign);
        param.put("subcampaign", subcampaign);
        param.put("subname", subcampaign);
        param.put("channel", channel);
        param.put("resultcount", numberoffers);
        param.put("userid", userid);
        param.put("params", jsonParams);
        param.put("api_payment_method", paymentMethod);
        param.put("transaction_id", transactionId);

        /* use api_params key to store values in the params json object to allow for logging */
        JSONObject inParam = new JSONObject(param.toString());
        param.put("api_params", inParam);
        param.put("upsize_indicator", "Y");
        param = ValidateParams.getLookupFromParams(settings, param, customer);

        /* Set defaults for model and paramneters from database */
        param.put("mojo", "1");
        param.put("dbparam", true);

        /* Obtain default epsilon from properties or obtain from input params */
        if (!paramsParams.has("mab")) {
            JSONObject mabParam = new JSONObject();
            mabParam.put("class", "mabone");
            mabParam.put("epsilon", settings.getEpsilon());
            param.put("mab", mabParam);
        } else {
            param.put("mab", paramsParams.getJSONObject("mab"));
        }
        param.put("in_params", paramsParams);

        JSONObject predictResult = new JSONObject();
        /* Primary prediction from EcosystemMaster.getPredictionResult */
        predictResult = ecosystemMaster.getPredictionResult(param);

        if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
        if (predictResult.has("ErrorMessage")) {
            predictResult.put("error", 1);
        }

        /* TODO MAKE THIS CONFIGURABLE IN THE WORKBENCH */
        /* stage specific JSON result */
        JSONObject result = new JSONObject();
        result.put("cache", predictResult.get("cache"));
        result.put("request_date", predictResult.get("datetime"));
        result.put("explore",predictResult.get("explore"));
        result.put("msisdn", customer);
        result.put("campaign_id", campaign);
        result.put("sub_campaign_id", subcampaign);
        result.put("session_id", param.get("uuid"));
        result.put("uuid", param.get("uuid"));
        result.put("in_params",
                new JSONObject()
                        .put("vodabucks_balance", param.get("in_params"))
                        .get("vodabucks_balance")
        );
        result.put("final_result", predictResult.getJSONArray("final_result"));
        if (param.has("payment_method_code"))
            result.put("payment_method",param.get("payment_method_code"));
        else
            result.put("payment_method",paymentMethod);

        long endTime = System.nanoTime();
        LOGGER.info( uuid + " - Overall API response: " + ((endTime - startTime) / 1000000) );

        return result.toString().intern();
    }


    /**************************************************************************************************************/

    /**
     * PERSONALITY API
     */

    /**
     * @param campaign     campaign
     * @param subcampaign  subcampaign
     * @param customer     customer
     * @param channel      channel
     * @param numberoffers numberoffers
     * @return Result
     */
    @ApiOperation(value = "Provide offers that form part of a campaign for particular customer.")
    @ApiResponse(code = 200, message = "Personality successfully completed")
    @RequestMapping(value = "/personalityRecommender", method = GET)
    public String getPersonalityRecommender(@RequestHeader Map<String, String> headers,
                                          @RequestParam(value = "campaign", defaultValue = "") String campaign,
                                          @RequestParam(value = "subcampaign", defaultValue = "", required = false) String subcampaign,
                                          @RequestParam(value = "customer", defaultValue = "") String customer,
                                          @RequestParam(value = "channel", defaultValue = "") String channel,
                                          @RequestParam(value = "numberoffers", defaultValue = "", required = false) int numberoffers,
                                          @RequestParam(value = "userid", defaultValue = "") String userid,
                                          @RequestParam(value = "params", defaultValue = "", required = false) String jsonParams) throws Exception {
        LOGGER.info("/personalityRecommender API");

        JSONObject paramsParams = new JSONObject();
        try {
            paramsParams = new JSONObject(JSONDecode.decode(jsonParams));
        } catch (org.json.JSONException e) {
            LOGGER.info("/personalityRecommender malformed params JSON input: " + jsonParams);
            return paramsParams.put("ErrorMessage", e).toString().intern();
        }

        /* Setup values from input params that will be placed in */
        JSONObject param = new JSONObject();
        String uuid = generateUUID();
        param.put("headers", headers);
        param.put("uuid", uuid);
        param.put("name", campaign);
        param.put("subcampaign", subcampaign);
        param.put("channel", channel);
        param.put("subname", subcampaign);
        param.put("resultcount", numberoffers);
        param.put("userid", userid);
        /* this is needed to not cause a stack overflow as adding current value of json object */
        JSONObject inParam = new JSONObject(param.toString());
        param.put("api_params", inParam);
        param = ValidateParams.getLookupFromParams(settings, param, customer);

        /* Set defaults for model and paramneters from database */
        param.put("mojo", "1");
        param.put("dbparam", true);

        /* Obtain default epsilon from properties or obtain from input params */
        if (!paramsParams.has("mab")) {
            JSONObject mabParam = new JSONObject();
            mabParam.put("class", "mabone");
            mabParam.put("epsilon", settings.getEpsilon());
            param.put("mab", mabParam);
        } else {
            param.put("mab", paramsParams.getJSONObject("mab"));
        }
        param.put("in_params", paramsParams);

        JSONObject predictResult = new JSONObject();
        /* Primary prediction from EcosystemMaster.getPredictionResult */
        predictResult = ecosystemMaster.getPredictionResult(param);
        if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
        if (predictResult.has("ErrorMessage")) {
            predictResult.put("error", 1);
        }
        JSONObject result = new JSONObject();
        result.put("cache", predictResult.get("cache"));
        result.put("request_date", predictResult.get("datetime"));
        result.put("explore",predictResult.get("explore"));
        result.put("customer", customer);
        result.put("campaign_id", campaign);
        result.put("uuid", param.get("uuid"));
        // result.put("params", param.get("params"));
        result.put("final_result", predictResult.getJSONArray("final_result"));

        return result.toString().intern();
    }

    /**
     * Update offers taken up by customers/msisdn
     *
     * @param documentJSON documentJSON
     * @return Result
     */
    @ApiOperation(value = "Update offers taken up by customers")
    @RequestMapping(value = "/personalityRecommender", method = PUT)
    public String putPersonalityRecommender(@RequestHeader Map<String, String> headers,
                                          @RequestParam(value = "document", defaultValue = "") String documentJSON) throws IOException, ParseException {
        LOGGER.info("/personalityRecommender PUT API");
        String response = ecosystemResponse.putResponse(JSONDecode.decode(documentJSON));
        if (response == null)
            LOGGER.error("/personalityRecommender PUT API input document: " + documentJSON);
        else
            LOGGER.info("/personalityRecommender PUT API response: " + response);
        return response;
    }

    /**************************************************************************************************************/

    /**
     * @param campaign     campaign
     * @param subcampaign  subcampaign
     * @param customer     customer
     * @param channel      channel
     * @return Result
     */
    @ApiOperation(value = "Provide spending personality scores for customers.")
    @ApiResponse(code = 200, message = "Personality successfully completed")
    @RequestMapping(value = "/getSpendingPersonality", method = GET)
    public String getSpendingPersonality(@RequestHeader Map<String, String> headers,
                                            @RequestParam(value = "campaign", defaultValue = "") String campaign,
                                            @RequestParam(value = "subcampaign", defaultValue = "", required = false) String subcampaign,
                                            @RequestParam(value = "customer", defaultValue = "") String customer,
                                            @RequestParam(value = "channel", defaultValue = "") String channel,
                                            @RequestParam(value = "userid", defaultValue = "") String userid,
                                            @RequestParam(value = "params", defaultValue = "", required = false) String jsonParams) throws Exception {
        LOGGER.info("/getSpendingPersonality API");

        JSONObject paramsParams = new JSONObject();
        try {
            paramsParams = new JSONObject(JSONDecode.decode(jsonParams));
        } catch (org.json.JSONException e) {
            LOGGER.info("/getSpendingPersonality malformed params JSON input: " + jsonParams);
            return paramsParams.put("ErrorMessage", e).toString().intern();
        }

        /* Setup values from input params that will be placed in */
        JSONObject param = new JSONObject();
        String uuid = generateUUID();
        param.put("headers", headers);
        param.put("uuid", uuid);
        param.put("name", campaign);
        param.put("customer", customer);
        param.put("subcampaign", subcampaign);
        param.put("channel", channel);
        param.put("subname", subcampaign);
        param.put("userid", userid);
        /* this is needed to not cause a stack overflow as adding current value of json object */
        JSONObject inParam = new JSONObject(param.toString());
        param.put("api_params", inParam);
        param = ValidateParams.getLookupFromParams(settings, param, customer);

        /* Set defaults for model and paramneters from database */
        param.put("mojo", "1");
        param.put("dbparam", true);

        /* Obtain default epsilon from properties or obtain from input params */
        if (!paramsParams.has("mab")) {
            JSONObject mabParam = new JSONObject();
            mabParam.put("class", "mabone");
            mabParam.put("epsilon", settings.getEpsilon());
            param.put("mab", mabParam);
        } else {
            param.put("mab", paramsParams.getJSONObject("mab"));
        }
        param.put("in_params", paramsParams);

        JSONObject predictResult = new JSONObject();
        /* Primary prediction from EcosystemMaster.getPredictionResult */
        predictResult = ecosystemMaster.getPredictionResult(param);
        if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
        if (predictResult.has("ErrorMessage")) {
            predictResult.put("error", 1);
        }
        JSONObject result = new JSONObject();
        result.put("cache", predictResult.get("cache"));
        result.put("request_date", predictResult.get("datetime"));
        result.put("explore",predictResult.get("explore"));
        result.put("customer", customer);
        result.put("campaign", campaign);
        result.put("uuid", param.get("uuid"));
        result.put("final_result", predictResult.getJSONArray("final_result"));

        return result.toString().intern();
    }

    /**
     * @param documentJSON documentJSON
     * @return Result
     */
    @ApiOperation(value = "Update offers taken up by customers")
    @RequestMapping(value = "/getSpendingPersonality", method = PUT)
    public String getSpendingPersonality(@RequestHeader Map<String, String> headers,
                                         @RequestParam(value = "document", defaultValue = "") String documentJSON) throws IOException, ParseException {
        LOGGER.info("/getSpendingPersonality PUT API");
        String response = ecosystemResponse.putResponse(JSONDecode.decode(documentJSON));
        if (response == null)
            LOGGER.error("/getSpendingPersonality PUT API input document: " + documentJSON);
        else
            LOGGER.info("/getSpendingPersonality PUT API response: " + response);
        return response;
    }

    /**************************************************************************************************************/

    /**
     * @param campaign     campaign
     * @param subcampaign  subcampaign
     * @param customer     customer
     * @param channel      channel
     * @return Result
     */
    @ApiOperation(value = "Behavior recommender used as personality, behavior and other itervention types.")
    @ApiResponse(code = 200, message = "Behavior recommender")
    @RequestMapping(value = "/behaviorRecommender", method = GET)
    public String behaviorRecommender(@RequestHeader Map<String, String> headers,
                                         @RequestParam(value = "campaign", defaultValue = "") String campaign,
                                         @RequestParam(value = "subcampaign", defaultValue = "", required = false) String subcampaign,
                                         @RequestParam(value = "customer", defaultValue = "") String customer,
                                         @RequestParam(value = "channel", defaultValue = "") String channel,
                                         @RequestParam(value = "userid", defaultValue = "") String userid,
                                         @RequestParam(value = "params", defaultValue = "", required = false) String jsonParams) throws Exception {
        LOGGER.info("/behaviorRecommender API");

        JSONObject paramsParams = new JSONObject();
        try {
            paramsParams = new JSONObject(JSONDecode.decode(jsonParams));
        } catch (org.json.JSONException e) {
            LOGGER.info("/behaviorRecommender malformed params JSON input: " + jsonParams);
            return paramsParams.put("ErrorMessage", e).toString().intern();
        }

        /* Setup values from input params that will be placed in */
        JSONObject param = new JSONObject();
        String uuid = generateUUID();
        param.put("headers", headers);
        param.put("uuid", uuid);
        param.put("name", campaign);
        param.put("customer", customer);
        param.put("subcampaign", subcampaign);
        param.put("channel", channel);
        param.put("subname", subcampaign);
        param.put("userid", userid);
        JSONObject inParam = new JSONObject(param.toString());
        param.put("api_params", inParam);
        param = ValidateParams.getLookupFromParams(settings, param, customer);

        /* Set defaults for model and paramneters from database */
        param.put("mojo", "1");
        param.put("dbparam", true);

        /* Obtain default epsilon from properties or obtain from input params */
        if (!paramsParams.has("mab")) {
            JSONObject mabParam = new JSONObject();
            mabParam.put("class", "mabone");
            mabParam.put("epsilon", settings.getEpsilon());
            param.put("mab", mabParam);
        } else {
            param.put("mab", paramsParams.getJSONObject("mab"));
        }
        param.put("in_params", paramsParams);

        JSONObject predictResult = new JSONObject();
        /* Primary prediction from EcosystemMaster.getPredictionResult */
        predictResult = ecosystemMaster.getPredictionResult(param);

        if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
        if (predictResult.has("ErrorMessage")) {
            predictResult.put("error", 1);
        }
        JSONObject result = new JSONObject();
        result.put("cache", predictResult.get("cache"));
        result.put("request_date", predictResult.get("datetime"));
        result.put("explore",predictResult.get("explore"));
        result.put("customer", customer);
        result.put("campaign", campaign);
        result.put("uuid", param.get("uuid"));
        result.put("final_result", predictResult.getJSONArray("final_result"));

        return result.toString().intern();
    }

    /**
     * @param documentJSON documentJSON
     * @return Result
     */
    @ApiOperation(value = "Update offers taken up by customers")
    @RequestMapping(value = "/behaviorRecommender", method = PUT)
    public String behaviorRecommender(@RequestHeader Map<String, String> headers,
                                            @RequestParam(value = "document", defaultValue = "") String documentJSON) throws IOException, ParseException {
        LOGGER.info("/behaviorRecommender PUT API");
        String response = ecosystemResponse.putResponse(JSONDecode.decode(documentJSON));
        if (response == null)
            LOGGER.error("/behaviorRecommender PUT API input document: " + documentJSON);
        else
            LOGGER.info("/behaviorRecommender PUT API response: " + response);
        return response;
    }

    /**************************************************************************************************************/

    /**
     * @param campaign     campaign
     * @param subcampaign  subcampaign
     * @param customer     customer
     * @param channel      channel
     * @return Result
     */
    @ApiOperation(value = "getFinancialWellnessScore scores for customers.")
    @ApiResponse(code = 200, message = "getFinancialWellnessScore successfully completed")
    @RequestMapping(value = "/getFinancialWellnessScore", method = GET)
    public String getFinancialWellnessScore(@RequestHeader Map<String, String> headers,
                                         @RequestParam(value = "campaign", defaultValue = "") String campaign,
                                         @RequestParam(value = "subcampaign", defaultValue = "", required = false) String subcampaign,
                                         @RequestParam(value = "customer", defaultValue = "") String customer,
                                         @RequestParam(value = "channel", defaultValue = "") String channel,
                                         @RequestParam(value = "userid", defaultValue = "") String userid,
                                         @RequestParam(value = "params", defaultValue = "", required = false) String jsonParams) throws Exception {
        LOGGER.info("/getFinancialWellnessScore API");

        JSONObject paramsParams = new JSONObject();
        try {
            paramsParams = new JSONObject(JSONDecode.decode(jsonParams));
        } catch (org.json.JSONException e) {
            LOGGER.info("/getFinancialWellnessScore malformed params JSON input: " + jsonParams);
            return paramsParams.put("ErrorMessage", e).toString().intern();
        }

        /* Setup values from input params that will be placed in */
        JSONObject param = new JSONObject();
        String uuid = generateUUID();
        param.put("headers", headers);
        param.put("uuid", uuid);
        param.put("name", campaign);
        param.put("customer", customer);
        param.put("subcampaign", subcampaign);
        param.put("channel", channel);
        param.put("subname", subcampaign);
        param.put("userid", userid);
        /* this is needed to not cause a stack overflow as adding current value of json object */
        JSONObject inParam = new JSONObject(param.toString());
        param.put("api_params", inParam);
        param = ValidateParams.getLookupFromParams(settings, param, customer);

        /* Set defaults for model and paramneters from database */
        param.put("mojo", "1");
        param.put("dbparam", true);

        /* Obtain default epsilon from properties or obtain from input params */
        if (!paramsParams.has("mab")) {
            JSONObject mabParam = new JSONObject();
            mabParam.put("class", "mabone");
            mabParam.put("epsilon", settings.getEpsilon());
            param.put("mab", mabParam);
        } else {
            param.put("mab", paramsParams.getJSONObject("mab"));
        }
        param.put("in_params", paramsParams);

        JSONObject predictResult = new JSONObject();
        /* Primary prediction from EcosystemMaster.getPredictionResult */
        predictResult = ecosystemMaster.getPredictionResult(param);
        if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
        if (predictResult.has("ErrorMessage")) {
            predictResult.put("error", 1);
        }
        JSONObject result = new JSONObject();
        result.put("cache", predictResult.get("cache"));
        result.put("request_date", predictResult.get("datetime"));
        result.put("explore",predictResult.get("explore"));
        result.put("customer", customer);
        result.put("campaign", campaign);
        result.put("uuid", param.get("uuid"));
        result.put("final_result", predictResult.getJSONArray("final_result"));

        return result.toString().intern();
    }

    /**
     * @param documentJSON documentJSON
     * @return Result
     */
    @ApiOperation(value = "getFinancialWellnessScore per customer")
    @RequestMapping(value = "/getFinancialWellnessScore", method = PUT)
    public String getFinancialWellnessScore(@RequestHeader Map<String, String> headers,
                                         @RequestParam(value = "document", defaultValue = "") String documentJSON) throws IOException, ParseException {
        LOGGER.info("/getFinancialWellnessScore PUT API");
        String response = ecosystemResponse.putResponse(JSONDecode.decode(documentJSON));
        if (response == null)
            LOGGER.error("/getFinancialWellnessScore PUT API input document: " + documentJSON);
        else
            LOGGER.info("/getFinancialWellnessScore PUT API response: " + response);
        return response;
    }

    /**************************************************************************************************************/


    /**
     * CORE RUNTIME FEATURES FROM GENERATION 2
     */

    /**
     * Model details. Example parameter: {'mojo':'my_mojo.zip'}
     *
     * @param valueJSON JSON parameter: {'mojo':'my_mojo.zip'}
     * @return Result of model details
     * @throws IOException Error
     */
    @ApiOperation(value = "Model details. Example parameter: {'mojo':'my_mojo.zip'}", response = String.class)
    @RequestMapping(value = "/modelDetail", method = RequestMethod.GET)
    public String modelDetail(
            @RequestParam(value = "model", defaultValue = "{'mojo':'my_mojo.zip'}") String valueJSON)
            throws Exception {
        LOGGER.info("/modelDetail params: " + valueJSON);
        ModelMojoWorkerH2O uPd = new ModelMojoWorkerH2O();
        return uPd.modelDetail(JSONDecode.decode(valueJSON)).toString().intern();
    }

    /**
     * Score model mojo from parameters
     *
     * @param valueJSON JSON Parameter: {'mojo':'model_mojo.zip','input': ['x','y'],'value': ['val_x', 'val_y']}
     * @param detail    Detail to return: all, basic or none
     * @return Result
     * @throws IOException Error
     */
    @ApiOperation(value = "Perform basic prediction on model with detail: none, basic or all. Example parameter: " +
            "{'mojo':'model_mojo.zip','input': ['x','y'],'value': ['val_x', 'val_y']}", response = String.class)
    @RequestMapping(value = "/runModelMojo", method = RequestMethod.GET)
    public String runModelMojo(
            @RequestParam(value = "value",
                    defaultValue = "{'mojo':'model_mojo.zip','input': ['x','y'],'value': ['val_x', 'val_y']} ") String valueJSON,
            @RequestParam(value = "detail",
                    defaultValue = "all") String detail)
            throws Exception {
        LOGGER.info("/runModelMojo params: " + valueJSON);

        JSONObject logStats = new JSONObject();
        logStats = ActivityLog.logStart(logStats);

        String uuid = UUID.randomUUID().toString();
        ValidateParams vp = new ValidateParams();

        JSONObject params = vp.validateRunModelMojoParams(JSONDecode.decode(valueJSON));
        if (params != null) {
            RunModelMojo m = new RunModelMojo(valueJSON);
            JSONObject predictResult = new JSONObject(m.runModelMojo(valueJSON, detail));
            logStats = ActivityLog.logStop(logStats);

            MongoCollection loggingCollection = settingsConnection.connectMongoDB(settings.getLoggingDatabase(), settings.getLoggingCollection());
            if (loggingCollection != null)
                addLoggingAsync(loggingCollection, params, predictResult, logStats, uuid);

            return String.valueOf(predictResult);
        } else {
            return "{\"ErrorMessage\":\"PredictorMaster:runModelMojo:E001: No 'input' parameter, or no data for input.\"}";
        }
    }

    /**
     * Score model from pre-loaded mojo as set in the properties file
     *
     * @param valueJSON Example: {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'},'mojo':'1', 'input':['x','y'], 'value':['val_x','val_y']} OR {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'}, 'mojo':'1', 'dbparam':true, lookup:{key:'customer',value:'1234567890'} }
     * @param detail    Detail to return: all, basic or none
     * @return Result
     */
    @ApiOperation(value = "Perform prediction on pre-loaded model with detail and push onto Kafka topic: none, basic or all. Perform a database lookup if properties file has been set. " +
            "Example parameter: Example: {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'},'mojo':'1', 'input':['x','y'], 'value':['val_x','val_y']} OR {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'}, 'mojo':'1', 'dbparam':true, lookup:{key:'customer',value:'1234567890'} }" +
            "", response = String.class)
    @RequestMapping(value = "/predictorResponsePreLoadKafKa", method = RequestMethod.GET)
    public String predictorResponsePreLoadKafKa(
            @RequestParam(value = "value",
                    defaultValue = "{'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'},'mojo':'1', 'input':['x','y'], 'value':['val_x','val_y']} " +
                            "OR {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'}, 'mojo':'1', 'dbparam':true, lookup:{key:'customer',value:'1234567890'} }") String valueJSON,
            @RequestParam(value = "detail",
                    defaultValue = "none") String detail) {
        LOGGER.info("/predictorResponsePreLoadKafKa params: " + valueJSON);
        try {
            JSONObject params = new JSONObject(JSONDecode.decode(valueJSON));
            return ecosystemMaster.getPredictionResultToKafka(params);
        } catch (Exception e) {
            LOGGER.error("PredictorMaster:predictorResponsePreLoadKafKa:E000: Param error: " + e);
            return "{\"ErrorMessage\":\"PredictorMaster:predictorResponsePreLoadKafKa:E000-1: Parameter error.\"}";
        }

    }

    /**
     * Score model from pre-loaded mojo as set in the properties file
     *
     * @param valueJSON Example: {'name':'predict1', 'mojo':'1','dbparam':true, lookup:{key:'customer_id',value:724578004}} OR if parameter is not
     *                  from database use: {'name':'predict1', 'mojo':'1..3','dbparam':false, 'input':['x','y'], 'value':['val_x','val_y']}
     *                  Use x:n to define the number of predictions to return as primary result, if the overall probability is not used.
     *                  Optional 'resultcount':3  if not present in parameter, then return one item
     * @param detail    Detail to return: all, basic or none
     * @return Result
     */
    @ApiOperation(value = "Perform prediction on pre-loaded model with detail: none, basic or all. Perform a database lookup if properties file has been set. " +
            "The predictor parameters are broken into two types namely, requiring all parameters via API or requiring a lookup key via API and extracting parameters " +
            "from a data source." +
            "Use this format for input prams only:" +
            "{'name':'predict1', 'mojo':'model_mojo.zip','dbparam':false,'input': ['x','y'],'value': ['val_x', 'val_y']}" +
            "Use this approach for inputs from data source:" +
            "{'name':'predict1', 'mojo':'model_mojo.zip','dbparam':true, lookup:{key:'customer',value:1234567890}} " +
            "If there is post-scoring logic, then ise this configuration:" +
            "{'name':'predict1', 'mojo':'1','mab':{'class':'mabone', 'epsilon':0.4},'dbparam':true, lookup:{key:'customer',value:1234567890}, param:{key:'value_field', value:30}}" +
            "", response = String.class)
    @RequestMapping(value = "/predictorResponsePreLoad", method = RequestMethod.GET)
    public String predictorResponsePreLoad(
            @RequestParam(value = "value",
                    defaultValue = "{'name':'predict1', 'mojo':'1..16','dbparam':false, 'input':['x','y'], 'value':['val_x','val_y'], 'x':1} " +
                            "OR {'name':'predict1', 'mojo':'1','dbparam':true, lookup:{key:'customer',value:'1234567890'}} + " +
                            "OR {'name':'predict1', 'mojo':'1','mab':{'class':'mabone', 'epsilon':0.4},'dbparam':true, lookup:{key:'customer',value:1234567890}, param:{key:'value_field', value:30}, resultcount:3}") String valueJSON,
            @RequestParam(value = "detail",
                    defaultValue = "all") String detail) {
        LOGGER.info("/predictorResponsePreLoad params: " + valueJSON);
        try {
            JSONObject params = new JSONObject(JSONDecode.decode(valueJSON));
            return ecosystemMaster.getPredictionResult(params).toString().intern();
        } catch (Exception e) {
            LOGGER.error("PredictorMaster:predictorResponsePreLoad:E000: Param error: " + e);
            return "{\"ErrorMessage\":\"PredictorMaster:predictorResponsePreLoad:E000-1: Parameter error.\"}";
        }

    }

}
