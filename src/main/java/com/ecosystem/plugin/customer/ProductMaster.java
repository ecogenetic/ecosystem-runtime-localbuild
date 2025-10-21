package com.ecosystem.plugin.customer;

import com.ecosystem.plugin.business.BusinessLogic;
import com.ecosystem.runtime.ProductMasterSuper;
import com.ecosystem.runtime.ValidateParams;
import com.ecosystem.utils.JSONDecode;
import com.ecosystem.utils.JSONFlattener;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLDecoder;
import java.text.ParseException;
import java.util.Map;
import java.util.UUID;

import static com.ecosystem.utils.GenerateUUID.generateUUID;

@CrossOrigin(origins = "*")
@RolesAllowed({"ADMIN", "USER"})
@RestController()
@SecurityScheme(type = SecuritySchemeType.APIKEY)
@ApiResponses(value = {
		@ApiResponse(responseCode = "200", description = "Successful",
				content = { @Content(mediaType = "application/json",
						schema = @Schema(implementation = ProductMaster.class)) }),
		@ApiResponse(responseCode = "400", description = "Error",
				content = @Content),
		@ApiResponse(responseCode = "404", description = "Error",
				content = @Content) })
@Tag(name = "Predictors", description = "Review model domain details, refresh model parameter loading and perform predictions. " +
		"There are two primary approaches to invoking a prediction for scoring via a model namely; " +
		"Invoke model and return a JSON response that can be used in any application," +
		" invoke model and deliver result onto a KAFKA topic of your choosing. " +
		" Model can also be tested by dynamically loading a MOJO, mostly used for testing purposes." +
			"The predictor parameters are broken into two types namely, requiring all parameters via API or requiring a lookup key via API and extracting parameters from a data source." +
			"Use this format for input params: \n " +
			"{'name':'predict1', 'mojo':'model_mojo.zip','dbparam':false,'input': ['x','y'],'value': ['val_x', 'val_y']}" +
			"\nUse this approach for inputs from data source:\n" +
			"{'name':'predict1', 'mojo':'model_mojo.zip','dbparam':true, lookup:{key:'customer',value:1234567890}} " +
			"\nIf there is post-scoring logic, then ise this configuration:\n" +
			"{'name':'predict1', 'mojo': '1','mab': {'class':'mabone', 'epsilon':0.4},'dbparam': true, lookup:{key:'customer',value: 1234567890}, param: {key:'value_field', value:30}}\n")

public class ProductMaster extends ProductMasterSuper {

	private static final Logger LOGGER = LogManager.getLogger(ProductMaster.class.getName());

	public ProductMaster() {
		super();
	}

	/**
	 * Primary Scoring Endpoint for Inference.
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@Operation(summary = "If no parameters are provided then the current use-case name is used and user is none. "
			+" Invocation payload: {\"campaign\":\"name\",\"subcampaign\":\"none\"," +
			"\"customer\":\"1111\",\"channel\":\"app\",\"numberoffers\":1,\"userid\":\"test\",\"params\":\"{}\"} " +
			"Note that the params will be different when a dynamic interaction model is used."
	)
	@PostMapping("/invocations")
	public String invoke(
			@RequestHeader Map<String, String> headers,
			@RequestBody String request
	) {
		LOGGER.info("/invocations API");
		JSONObject predictResult = new JSONObject();

		try {
			JSONObject inpObj = new JSONObject(request);

			/************ Validate and use defaults ***********/
			String campaign = settings.getProjectDeploymentID();
			if (inpObj.has("campaign"))
				campaign = String.valueOf(inpObj.get("campaign"));

			String subcampaign = campaign;
			if (inpObj.has("subcampaign"))
				subcampaign = String.valueOf(inpObj.get("subcampaign"));

			String channel = "api";
			if (inpObj.has("channel"))
				channel = String.valueOf(inpObj.get("channel"));

			int numberoffers = 1;
			if (inpObj.has("numberoffers"))
				numberoffers = Integer.parseInt(String.valueOf(inpObj.get("numberoffers")));

			String userid = "api";
			if (inpObj.has("userid"))
				userid = String.valueOf(inpObj.get("userid"));

			String params = "{}";
			if (inpObj.has("params"))
				params = (String) inpObj.get("params");

			String customer = "none";
			if (inpObj.has("customer"))
				customer = String.valueOf(inpObj.get("customer"));

			JSONObject paramsParams = new JSONObject();
			try {
				String in_params = URLDecoder.decode(params);
				if (in_params.startsWith("\"")) in_params = in_params.substring(1, in_params.length() - 1).replaceAll("\\\\", "");
				paramsParams = new JSONObject(in_params);
			} catch (org.json.JSONException e) {
				LOGGER.info("/offerRecommendations malformed params JSON input: " + params);
				return paramsParams.put("ErrorMessage", e).toString().intern();
			}

			/************ Setup values from input params that will be placed in **********/
			JSONObject param = new JSONObject();
			String uuid = generateUUID();
			/** param.put("headers", headers); */
			param.put("uuid", uuid);
			param.put("UPDATE", this.UPDATE);
			LOGGER.info("/invocations:UUID: " + uuid + " predictor: " + campaign);

			param.put("name", campaign);
			param.put("customer", customer);
			param.put("campaign", campaign);
			param.put("subcampaign", subcampaign);
			param.put("channel", channel);
			param.put("subname", subcampaign);
			param.put("resultcount", numberoffers);
			param.put("userid", userid);
			param.put("mojo", "1");

			/* this is needed to not cause a stack overflow as adding current value of json object */
			JSONObject inParam = new JSONObject(param.toString());
			param.put("api_params", inParam);

			/************ Set defaults for model and paramneters from database ***********/
			param.put("in_params", paramsParams);
			if (paramsParams.has("input")) {
				param.put("input", paramsParams.getJSONArray("input"));
				param.put("value", paramsParams.getJSONArray("value"));
				param.put("lookup", new JSONObject().put("value", customer).put("key", "customer"));
				param.put("dbparam", false);
			} else {
				param.put("dbparam", true);
				param = ValidateParams.getLookupFromParams(settings, param, customer);
			}

			/************ Obtain default epsilon from properties or obtain from input params ***********/
			if (!paramsParams.has("mab")) {
				JSONObject mabParam = new JSONObject();
				mabParam.put("class", "mabone");
				mabParam.put("epsilon", settings.getEpsilon());
				param.put("mab", mabParam);
			} else {
				param.put("mab", paramsParams.getJSONObject("mab"));
			}

			/**************** Primary prediction from EcosystemMaster.getPredictionResult **************/
			predictResult = ecosystemMaster.getPredictionResult(mongoClient, param);
            if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
			if (predictResult.has("ErrorMessage")) {
				predictResult.put("error", 1);
			}
			predictResult.remove("predict_result");

			String detail = "full";
			if (paramsParams.has("detail"))
				detail = paramsParams.getString("detail");

			/**************** Special prediction approaches: Spam **************/
			if (detail.contains("spam") || subcampaign.contains("spam")) {
				JSONObject newResult = new JSONObject();
				newResult.put("uuid", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("uuid"));
				newResult.put("offer", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("offer"));
				newResult.put("ham_confidence", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("ham_confidence"));
				newResult.put("spam_confidence", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("spam_confidence"));
				newResult.put("spam", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("spam"));
				predictResult = newResult;
			}

			/**************** Final step to decide if json needs to be flattened **************/
			if (paramsParams.has("flatten_json") && Boolean.valueOf(String.valueOf(paramsParams.opt("flatten_json")))) {
				predictResult = JSONFlattener.flatten(predictResult);
			}

		} catch (Exception e) {
			e.printStackTrace();
			predictResult.put("ErrorMessage", e.getMessage());
		}

		this.UPDATE = setFinal(false, predictResult);

		return predictResult.toString();
	}

	/**
	 * Update responses based on predictions.
	 *
	 * @param documentJSON documentJSON
	 * @return Result
	 */
	@Operation(description = "Update response based on recommendation accepted (Async): " +
			"{\"uuid\": \"dcb54a23-0737-4768-845d-48162598c0f7\", \"offers_accepted\": [{\"offer_name\": \"OFFER_A\"}], \"channel_name\": \"app\"}",
			summary = "Update response based on predictions accepted")
	@PostMapping("/response")
	public String processResponse(@RequestHeader Map<String, String> headers,
								  @RequestBody String documentJSON) throws Exception {
		LOGGER.info("/response POST API");
		String response = "Success";

		try {
			ecosystemResponse.putResponseReturnDetailAsync(JSONDecode.decode(documentJSON));
		} catch (Exception e) {
			e.printStackTrace();
			JSONObject error = new JSONObject().put("ErrorMessage", e.getMessage());
			response = error.toString();
		}

		return "{\"message\": \"" + response + "\"}";
	}

	/**
	 * Business logic service.
	 *
	 * @param params JSONObject with params
	 * @return Result
	 */
	@Operation(description = "Access the business logic or other calculations.",
			summary = "Business logic")
	@RequestMapping(value = "/business", method = RequestMethod.POST)
	public String processBusiness(@RequestHeader Map<String, String> headers,
								  @RequestBody String params) throws Exception {
		LOGGER.info("/business POST API");
		String response = "Success";

		try {
			JSONObject paramsObj = new JSONObject(params);

			this.UPDATE = setFinal(false, new JSONObject());
			paramsObj.put("UPDATE", this.UPDATE);

			return BusinessLogic.getValues(paramsObj).toString();

		} catch (Exception e) {
			e.printStackTrace();
			JSONObject error = new JSONObject().put("ErrorMessage", e.getMessage());
			response = error.toString();
			return "{\"ErrorMessage\": \"" + response + "\"}";
		}

	}

	/**
	 * Update responses based on predictions.
	 *
	 * @param documentJSON documentJSON
	 * @return Result
	 */
	@Operation(description = "Update response based on recommendation accepted and return valid response: " +
			"{\"uuid\": \"dcb54a23-0737-4768-845d-48162598c0f7\", \"offers_accepted\": [{\"offer_name\": \"OFFER_A\"}], \"channel_name\": \"app\"}",
			summary = "Update response based on predictions accepted")
	@RequestMapping(value = "/responseResult", method = RequestMethod.POST)
	public String processResponseResult(@RequestHeader Map<String, String> headers,
										@RequestBody String documentJSON) throws Exception {
		LOGGER.info("/responseResult POST API");
		String response = "Error";

		try {
			JSONObject responseObj = ecosystemResponse.putResponseReturnDetail(JSONDecode.decode(documentJSON));
			if (responseObj.has("uuid"))
				response = responseObj.getString("uuid");
			else
				response = "Error: UUID not found.";
		} catch (Exception e) {
			e.printStackTrace();
			JSONObject error = new JSONObject().put("ErrorMessage", "Validate that uuid is available in log. " + e.getMessage());
			response = error.toString();
		}

		return "{\"message\": \"" + response + "\"}";
	}

	/**************************************************************************************************************/

	/**
	 * Prediction case is determined from properties file setup: mojo's, feature store, and other settings.
	 * <p>
	 * Recommender Case:
	 * From paramsParams - balance enquiry example: {msisdn:0828811817,in_balance:50,voice_balance:12,data_balance:400,n_offers:1}
	 * <p>
	 * Recommender Case:
	 * {'name':'rechargerecommender', 'mojo':'1','mab':{'class':'mabone', 'epsilon':0.4},'dbparam':true, lookup:{key:'msisdn',value:849999330}, param:{key:'in_recharge', value:100}, resultcount:2}
	 *
	 * @param campaign     campaign
	 * @param subcampaign  subcampaign
	 * @param customer     customer
	 * @param channel      channel
	 * @param numberoffers numberoffers
	 * @return Result
	 */
	@Operation(summary = "Provide offers that form part of a campaign for particular customer. When no feature store is present, use this in params: {'input': ['key1','key2'],'value': ['value1', 'value2']}")
	@RequestMapping(value = "/offerRecommendations",  method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	public String getOfferRecommendations(@RequestHeader Map<String, String> headers,
										  @RequestParam(name = "campaign", defaultValue = "") String campaign,
										  @RequestParam(name = "subcampaign", defaultValue = "", required = false) String subcampaign,
										  @RequestParam(name = "customer", defaultValue = "") String customer,
										  @RequestParam(name = "channel", defaultValue = "") String channel,
										  @RequestParam(name = "numberoffers", defaultValue = "", required = false) int numberoffers,
										  @RequestParam(name = "userid", defaultValue = "") String userid,
										  @RequestParam(name = "params", defaultValue = "", required = false) String params) throws Exception {
		LOGGER.info("/offerRecommendations API");
        JSONObject predictResult = new JSONObject();
        JSONObject paramsParams = new JSONObject();

        try {

            try {
                String in_params = URLDecoder.decode(params);
                if (in_params.startsWith("\"")) in_params = in_params.substring(1, in_params.length() - 1).replaceAll("\\\\", "");
                paramsParams = new JSONObject(in_params);
            } catch (org.json.JSONException e) {
                LOGGER.info("/offerRecommendations malformed params JSON input: " + params);
                return paramsParams.put("ErrorMessage", e).toString().intern();
            }

            /************ Setup values from input params that will be placed in **********/
            JSONObject param = new JSONObject();
            String uuid = generateUUID();
            /** param.put("headers", headers); */
            param.put("uuid", uuid);
            param.put("UPDATE", this.UPDATE);
            LOGGER.info("/invocations:UUID: " + uuid + " predictor: " + campaign);

            param.put("name", campaign);
            param.put("customer", customer);
            param.put("campaign", campaign);
            param.put("subcampaign", subcampaign);
            param.put("channel", channel);
            param.put("subname", subcampaign);
            param.put("resultcount", numberoffers);
            param.put("userid", userid);
            param.put("mojo", "1");

            /* this is needed to not cause a stack overflow as adding current value of json object */
            JSONObject inParam = new JSONObject(param.toString());
            param.put("api_params", inParam);

            /************ Set defaults for model and paramneters from database ***********/
            param.put("in_params", paramsParams);
            if (paramsParams.has("input")) {
                param.put("input", paramsParams.getJSONArray("input"));
                param.put("value", paramsParams.getJSONArray("value"));
                param.put("lookup", new JSONObject().put("value", customer).put("key", "customer"));
                param.put("dbparam", false);
            } else {
                param.put("dbparam", true);
                param = ValidateParams.getLookupFromParams(settings, param, customer);
            }

            /************ Obtain default epsilon from properties or obtain from input params ***********/
            if (!paramsParams.has("mab")) {
                JSONObject mabParam = new JSONObject();
                mabParam.put("class", "mabone");
                mabParam.put("epsilon", settings.getEpsilon());
                param.put("mab", mabParam);
            } else {
                param.put("mab", paramsParams.getJSONObject("mab"));
            }

            /**************** Primary prediction from EcosystemMaster.getPredictionResult **************/
            predictResult = ecosystemMaster.getPredictionResult(mongoClient, param);
            if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
            if (predictResult.has("ErrorMessage")) {
                predictResult.put("error", 1);
            }
            predictResult.remove("predict_result");

            String detail = "full";
            if (paramsParams.has("detail"))
                detail = paramsParams.getString("detail");

            /**************** Special prediction approaches: Spam **************/
            if (detail.contains("spam") || subcampaign.contains("spam")) {
                JSONObject newResult = new JSONObject();
                newResult.put("uuid", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("uuid"));
                newResult.put("offer", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("offer"));
                newResult.put("ham_confidence", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("ham_confidence"));
                newResult.put("spam_confidence", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("spam_confidence"));
                newResult.put("spam", predictResult.getJSONArray("final_result").getJSONObject(0).getJSONObject("result_full").get("spam"));
                predictResult = newResult;
            }

            /**************** Final step to decide if json needs to be flattened **************/
            if (paramsParams.has("flatten_json") && Boolean.valueOf(String.valueOf(paramsParams.opt("flatten_json")))) {
                predictResult = JSONFlattener.flatten(predictResult);
            }

        } catch (Exception e) {
            e.printStackTrace();
            predictResult.put("ErrorMessage", e.getMessage());
        }

		this.UPDATE = setFinal(false, predictResult);

		return predictResult.toString();

//
//		JSONObject paramsParams = new JSONObject();
//		try {
//			String in_params = URLDecoder.decode(jsonParams);
//			if (in_params.startsWith("\"")) in_params = in_params.substring(1, in_params.length() - 1).replaceAll("\\\\", "");
//			paramsParams = new JSONObject(in_params);
//		} catch (org.json.JSONException e) {
//			LOGGER.info("/offerRecommendations malformed params JSON input: " + jsonParams);
//			return paramsParams.put("ErrorMessage", e).toString();
//		}
//
//		/* Setup values from input params that will be placed in */
//		JSONObject param = new JSONObject();
//		String uuid = generateUUID();
//		param.put("headers", headers);
//		param.put("uuid", uuid);
//		param.put("UPDATE", this.UPDATE);
//
//		LOGGER.info("/invocations:UUID: " + uuid + " predictor: " + campaign);
//
//		param.put("customer", customer);
//		param.put("name", campaign);
//		param.put("subcampaign", subcampaign);
//		param.put("channel", channel);
//		param.put("subname", subcampaign);
//		param.put("resultcount", numberoffers);
//		param.put("userid", userid);
//		/* this is needed to not cause a stack overflow as adding current value of json object */
//		JSONObject inParam = new JSONObject(param.toString());
//		param.put("api_params", inParam);
//
//		/** Set defaults for model and paramneters from database */
//		param.put("in_params", paramsParams);
//		if (paramsParams.has("input")) {
//			param.put("input", paramsParams.getJSONArray("input"));
//			param.put("value", paramsParams.getJSONArray("value"));
//			param.put("lookup", new JSONObject().put("value", customer).put("key", "customer"));
//			param.put("dbparam", false);
//		} else {
//			param.put("dbparam", true);
//			param = ValidateParams.getLookupFromParams(settings, param, customer);
//		}
//
//		param.put("mojo", "1");
//
//		/** Obtain default epsilon from properties or obtain from input params */
//		if (!paramsParams.has("mab")) {
//			JSONObject mabParam = new JSONObject();
//			mabParam.put("class", "mabone");
//			mabParam.put("epsilon", settings.getEpsilon());
//			param.put("mab", mabParam);
//		} else {
//			param.put("mab", paramsParams.getJSONObject("mab"));
//		}
//
//		/** Primary prediction from EcosystemMaster.getPredictionResult */
//		JSONObject predictResult = new JSONObject();
//		predictResult = ecosystemMaster.getPredictionResult(mongoClient, param);
//		if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
//		if (predictResult.has("ErrorMessage")) {
//			predictResult.put("error", 1);
//		}
//
//		predictResult.remove("predict_result");
//
//		this.UPDATE = setFinal(false, predictResult);
//
//		return predictResult.toString();
	}

	/**
	 * Update offers taken up by customers/msisdn
	 *
	 * @param documentJSON documentJSON
	 * @return Result
	 */
	@Operation(description = "Update response based on recommendation accepted (Async): " +
			"{\"uuid\": \"dcb54a23-0737-4768-845d-48162598c0f7\", \"offers_accepted\": [{\"offer_name\": \"OFFER_A\"}], \"channel_name\": \"app\"}" +
			"", summary = "Generic prediction scoring endine for recommenders.")
	@RequestMapping(value = "/offerRecommendations",  method = RequestMethod.PUT)
	@ResponseStatus(HttpStatus.OK)
	public String putOfferRecommendations(@RequestHeader Map<String, String> headers,
										  @RequestParam(name = "document", defaultValue = "") String documentJSON) throws IOException, ParseException {
		LOGGER.info("/offerRecommendations PUT API");
		LOGGER.info(documentJSON);

		String response = "Success";
		try {
			ecosystemResponse.putResponseReturnDetailAsync(JSONDecode.decode(documentJSON));
		} catch (Exception e) {
			e.printStackTrace();
			JSONObject error = new JSONObject().put("ErrorMessage", "Validate that uuid is available in log. " + e.getMessage());
			response = error.toString();
		}

		return "{\"message\": \"" + response + "\"}";
	}

	/**
	 * Update offers taken up by customers/msisdn
	 *
	 * @param documentJSON documentJSON
	 * @return Result
	 */
	@Operation(description = "Update response based on recommendation accepted and returns valid result: " +
			"{\"uuid\": \"dcb54a23-0737-4768-845d-48162598c0f7\", \"offers_accepted\": [{\"offer_name\": \"OFFER_A\"}], \"channel_name\": \"app\"}" +
			"", summary = "Generic prediction scoring endine for recommenders.")
	@RequestMapping(value = "/offerRecommendationsResult",  method = RequestMethod.PUT)
	@ResponseStatus(HttpStatus.OK)
	public String putOfferRecommendationsResult(@RequestHeader Map<String, String> headers,
										  @RequestParam(name = "document", defaultValue = "") String documentJSON) throws IOException, ParseException {
		LOGGER.info("/offerRecommendationsResult PUT API");
		LOGGER.info(documentJSON);

		String response;
		try {
			JSONObject responseObj = ecosystemResponse.putResponseReturnDetail(JSONDecode.decode(documentJSON));
			if (responseObj != null)
				response = responseObj.getString("uuid");
			else
				response = "none";
		} catch (Exception e) {
			e.printStackTrace();
			JSONObject error = new JSONObject().put("ErrorMessage", "Validate that uuid is available in log. " + e.getMessage());
			response = error.toString();
		}

		return "{\"message\": \"" + response + "\"}";
	}

	/**
	 * Score model from pre-loaded mojo as set in the properties file
	 *
	 * @param valueJSON Example: {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'},'mojo':'1', 'input':['x','y'], 'value':['val_x','val_y']} OR {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'}, 'mojo':'1', 'dbparam':true, lookup:{key:'customer',value:'1234567890'} }
	 * @param detail    Detail to return: all, basic or none
	 * @return Result
	 */
	@Operation(description = "Perform prediction on pre-loaded model with detail and push onto Kafka topic: none, basic or all. Perform a database lookup if properties file has been set. " +
			"Example parameter: Example: {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'},'mojo':'1', 'input':['x','y'], 'value':['val_x','val_y']} OR {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'}, 'mojo':'1', 'dbparam':true, lookup:{key:'customer',value:'1234567890'} }" +
			"", summary = "Perform prediction on pre-loaded model with detail and push onto Kafka topic")
	@RequestMapping(value = "/predictorResponsePreLoadKafKa", method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	public String predictorResponsePreLoadKafKa(
			@RequestHeader Map<String, String> headers,
			@RequestParam(name = "value",
					defaultValue = "{'name': 'predict1', 'kafka': {'TOPIC_NAME': 'ecosystem1', 'log': 'true'},'mojo': '1', 'input': ['x', 'y'], 'value': ['val_x', 'val_y']} " +
							"OR {'name': 'predict1', 'kafka': {'TOPIC_NAME': 'ecosystem1','log': 'true'}, 'mojo': '1', 'dbparam': true, lookup: {key: 'customer', value: '1234567890'} }") String valueJSON,
			@RequestParam(name = "detail",
					defaultValue = "none") String detail) {
		LOGGER.info("/predictorResponsePreLoadKafKa params: " + valueJSON);
		try {
			JSONObject params = new JSONObject(JSONDecode.decode(valueJSON));
			this.UPDATE = setFinal(false, params);
			params.put("UPDATE", this.UPDATE);
			return ecosystemMaster.getPredictionResultToKafka(mongoClient, params);
		} catch (Exception e) {
			LOGGER.error("PredictorMaster:predictorResponsePreLoadKafKa:E000: Param error: " + e);
			return "{\"ErrorMessage\": \"PredictorMaster:predictorResponsePreLoadKafKa:E000-1: Parameter error.\"}";
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
	@Operation(description = "Perform prediction on pre-loaded model with detail: none, basic or all. Perform a database lookup if properties file has been set. " +
			"The predictor parameters are broken into two types namely, requiring all parameters via API or requiring a lookup key via API and extracting parameters from a data source. Use this format for input prams only:" +
			"{'name': 'predict1', 'mojo': 1, 'dbparam': false, 'input': ['x','y'], 'value': ['val_x', 'val_y'], lookup: {'key': '', 'value': ''}}" +
			"Use this approach for inputs from data source:" +
			"{'name': 'predict1', 'mojo': 'model_mojo.zip', 'dbparam': true, lookup: {key: 'customer', value: 1234567890}} " +
			"If there is post-scoring logic, then ise this configuration:" +
			"{'name': 'predict1', 'mojo': '1', 'mab': {'class': 'mabone', 'epsilon': 0.4},'dbparam': true, lookup: {key: 'customer', value: 1234567890}, param: {key: 'value_field', value: 30}}" +
			"", summary = "Perform prediction on pre-loaded model with detail.")
	@RequestMapping(value = "/predictorResponsePreLoad", method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	public String predictorResponsePreLoad(
			@RequestHeader Map<String, String> headers,
			@RequestParam(name = "value",
					defaultValue = "{'name': 'predict1', 'mojo': '1..16', 'dbparam': false, 'input': ['x','y'], 'value': ['val_x', 'val_y'], 'x': 1} " +
							"OR {'name': 'predict1', 'mojo': '1','dbparam': true, lookup: {key: 'customer',value: '1234567890'}} + " +
							"OR {'name': 'predict1', 'mojo': '1','mab': {'class': 'mabone', 'epsilon': 0.4},'dbparam': true, lookup: {key: 'customer',value: 1234567890}, param: {key: 'value_field', value: 30}, resultcount: 3}") String valueJSON,
			@RequestParam(name = "detail",
					defaultValue = "all") String detail) throws Exception {
		LOGGER.info("/predictorResponsePreLoad params: " + valueJSON);
		String uuid = UUID.randomUUID().toString();
		JSONObject params = new JSONObject(JSONDecode.decode(valueJSON));
		params.put("uuid", uuid);
		params.put("UPDATE", this.UPDATE);
		this.UPDATE = setFinal(false, params);
		return ecosystemMaster.getPredictionResult(mongoClient, params).toString();
	}

}
