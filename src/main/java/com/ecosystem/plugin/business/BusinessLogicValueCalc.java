package com.ecosystem.plugin.business;

import com.ecosystem.utils.DataTypeConversions;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalTime;
import java.time.ZoneId;

public class BusinessLogicValueCalc {

    private static final Logger LOGGER = LogManager.getLogger(BusinessLogicValueCalc.class.getName());

    public static JSONObject valueCalc(JSONObject params) {
        /*
         Extract the data required to perform the value calc from params, in particular the offer matrix record for the
         offer from the offer matrix, the record from the feature store for the customer and the propensity
        */
        JSONObject singleOffer = new JSONObject();
        JSONObject featuresObj = new JSONObject();
        double p = 0;
        try {
            singleOffer = getSingleOffer(params);
            if (singleOffer.isEmpty()) {
                params = populateErrorDefaults(params,"BusinessLogicValueCalc:E000: Offer matrix record not found in params passed to value calc, returning -999");
                return params;
            }

            featuresObj = getFeaturesObj(params);
            if (featuresObj.isEmpty()) {
                params = populateErrorDefaults(params, "BusinessLogicValueCalc:E001: Feature store record not found in params passed to value calc, returning -999");
                return params;
            }

            p = getPropensity(params);
            if (p == -1) {
                p = 0.0;
                LOGGER.warn("BusinessLogicValueCalc:W001: Propensity value not found in params passed to value calc, using p = 0");
            }
        } catch (Exception e) {
            params = populateErrorDefaults(params, "BusinessLogicValueCalc:E003: Error extracting required information from params, returning -999. params: "+params.toString());
            return params;
        }

        /*
        Check whether a cohort_id is present for the customer and the offer and if it is return -999 if the customer
        and offer cohort_id do not match
         */
        try {
            //Check if the offer matrix has a cohort_id column. If not, then assume no cohort processing is required
            if (singleOffer.has("cohort_id")) {
                String offerCohortId = singleOffer.get("cohort_id").toString();
                String customerCohortId = getCustomerCohortID(params);
                if (!checkCohortIDValidity(customerCohortId,params)) {
                    customerCohortId = "0";
                }
                //Check if the customer and offer cohort_id match, if not return -999
                if (!customerCohortId.equals(offerCohortId)) {
                    LOGGER.info("BusinessLogicValueCalc:I000: customer and cohort_id not matching, returning -999. Offer cohort_id: "+offerCohortId+", customer_id: "+customerCohortId);
                    populateErrorDefaults(params,"");
                    return params;
                }
            }
        } catch (Exception e) {
            params = populateErrorDefaults(params, "BusinessLogicValueCalc:E004: cohort_id processing failed. params: "+params.toString());
            return params;
        }

        /*
         Extract the parameters to be used in the value calculation from the linked dynamic corpora
        */
        JSONObject extracted_value_calc_params = getValueCalcParams(params,featuresObj,singleOffer);

        /*
        Perform the value calculation and return the results
         */
        double offer_value = 0.0;
        double cop_car_value = 0.0;
        double offer_matrix_alpha = 0.0;

        if (singleOffer.has("offer_price"))
            offer_value = DataTypeConversions.getDouble(singleOffer, "offer_price");
        if (singleOffer.has("price"))
            offer_value = DataTypeConversions.getDouble(singleOffer, "price");
        if (singleOffer.has("alpha"))
            offer_matrix_alpha = DataTypeConversions.getDouble(singleOffer, "alpha");

        if (!singleOffer.has("cop_car")) {
            params = populateErrorDefaults(params, "BusinessLogicValueCalc:E005: cop_car not found in offer matrix record, returning -999");
            return params;
        }
        String cop_car = DataTypeConversions.getString(singleOffer.get("cop_car")).toLowerCase();
        if (featuresObj.has(cop_car)) {
            /* if cop_car has a null or NaN it should be set to 0.0 */
            try {
                cop_car_value = featuresObj.getDouble(cop_car);
            } catch (Exception e) {
                cop_car_value = 999.0;
                LOGGER.error("BusinessLogicValueCalc:E004: copcar calculation failed, using copcar = 999 ".concat(cop_car));
            }
        } else {
            cop_car_value = 999.0;
            LOGGER.warn("BusinessLogicValueCalc:W001: copcar calculation failed as field not found in feature store, using copcar = 999 ".concat(cop_car));
        }

        double value_multiplier = extracted_value_calc_params.getDouble("value_multiplier");
        double copcar_multiplier = extracted_value_calc_params.getDouble("copcar_multiplier");
        double alpha_multiplier = extracted_value_calc_params.getDouble("alpha_multiplier");
        double propensity_multiplier = extracted_value_calc_params.getDouble("propensity_multiplier");
        double value_power = extracted_value_calc_params.getDouble("value_power");
        double copcar_power = extracted_value_calc_params.getDouble("copcar_power");
        double alpha_power = extracted_value_calc_params.getDouble("alpha_power");
        double propensity_power = extracted_value_calc_params.getDouble("propensity_power");

        double propensity_term = 0.0;
        try {
            propensity_term = propensity_multiplier * Math.pow(p,propensity_power);
        } catch (Exception e) {
            LOGGER.error("BusinessLogicValueCalc:E005: propensity calculation failed, using propensity = 0");
        }
        double value_term = -999;
        try {
            value_term = value_multiplier * Math.pow(offer_value,value_power);
        } catch (Exception e) {
            LOGGER.error("BusinessLogicValueCalc:E006: value calculation failed, using value = -999");
        }
        double cop_car_term = 999;
        try {
            cop_car_term = copcar_multiplier * Math.pow(cop_car_value,copcar_power);
        } catch (Exception e) {
            LOGGER.error("BusinessLogicValueCalc:E007: copcar calculation failed, using copcar = 999");
        }
        double alpha_term = -999;
        try {
            alpha_term = alpha_multiplier * Math.pow(offer_matrix_alpha,alpha_power);
        } catch (Exception e) {
            LOGGER.error("BusinessLogicValueCalc:E008: alpha calculation failed, using alpha = -999");
        }

        double bundle_value = ((value_term - cop_car_term) * alpha_term);
        double final_value = propensity_term * bundle_value;

        JSONObject business_logic_results = new JSONObject();
        if (params.has("business_logic_results")) {
            business_logic_results = params.getJSONObject("business_logic_results");
            business_logic_results.put("bundle_value", bundle_value);
            business_logic_results.put("final_value", final_value);
            business_logic_results.put("modified_offer_score", final_value);
        } else {
            business_logic_results.put("bundle_value", bundle_value);
            business_logic_results.put("final_value", final_value);
            business_logic_results.put("modified_offer_score", final_value);
        }

        params.put("business_logic_results", business_logic_results);

        return params;
    }

    private static JSONObject getSingleOffer(JSONObject params) {
        if (params.has("singleOffer"))
            return params.getJSONObject("singleOffer");

        if (params.has("offerMatrixWithKey") && params.has("optionsDoc")) {
            JSONObject offerMatrixWithKey = params.getJSONObject("offerMatrixWithKey");
            JSONObject optionsDoc = params.getJSONObject("optionsDoc");
            String optionKey = optionsDoc.getString("optionKey");
            if (offerMatrixWithKey.has(optionKey)) {
                return offerMatrixWithKey.getJSONObject(optionKey);
            }
        }

        return new JSONObject();
    }

    private static JSONObject getFeaturesObj(JSONObject params) {
        if (params.has("featuresObj")) {
            return params.getJSONObject("featuresObj");
        }
        return new JSONObject();
    }

    private static double getPropensity(JSONObject params) {
        if (params.has("propensity")) {
            return params.getDouble("propensity");
        }

        if (params.has("p")) {
            return params.getDouble("p");
        }

        if (params.has("arm_reward")) {
            return params.getDouble("arm_reward");
        }

        return -1;
    }

    private static String getCustomerCohortID(JSONObject params) {
        String customerCohortId = "0";

        //Check if there is a place to lookup the cohort_id for the customer the use 0
        if (!params.getJSONObject("preloadCorpora").has("msisdn_cohort") && !params.getJSONObject("featuresObj").has("cohort_id")) {
            LOGGER.warn("getCustomerCohortID:W001: cohort_id found in offer matrix but no msisdn in preloadCorpora or cohort_id found in featuresObj. Using customer cohort_id 0");
            return customerCohortId;
        }

        //Attempt to look up the cohort_id for the customer, if the look up fails assume cohort_id = 0
        if (params.getJSONObject("featuresObj").has("cohort_id")) {
            customerCohortId = params.getJSONObject("featuresObj").get("cohort_id").toString();
            return customerCohortId;
        }

        String lookup = params.getJSONObject("lookup").get("value").toString();
        JSONObject msisdn_cohort = params.getJSONObject("preloadCorpora").getJSONObject("msisdn_cohort");
        if (!msisdn_cohort.has(lookup)) {
            LOGGER.warn("getCustomerCohortID:W002: customer not found in msisdn_cohort preload corpora, using cohort_id 0");
            return customerCohortId;
        }
        JSONObject customer_msisdn_cohort = msisdn_cohort.getJSONObject(lookup);
        if (!customer_msisdn_cohort.has("cohort_id")) {
            LOGGER.warn("getCustomerCohortID:W002: cohort_id field not found in customer record in msisdn_cohort preload corpora, using cohort_id 0");
            return customerCohortId;
        }

        customerCohortId = customer_msisdn_cohort.get("cohort_id").toString();
        return customerCohortId;
    }

    private static boolean checkCohortIDValidity(String cohort_id, JSONObject params) {
        boolean validity = false;
        if (!params.getJSONObject("preloadCorpora").has("cohort_identification")) {
            return validity;
        }

        JSONObject cohortDetails = params.getJSONObject("preloadCorpora").getJSONObject("cohort_identification");

        if (cohortDetails.has("active_cohort_yn")) {
            if (cohortDetails.getString("active_cohort_yn").equals("N")) {
                return validity;
            }
        }

        ZoneId z = ZoneId.of("Africa/Johannesburg") ;
        LocalTime now = LocalTime.now(z);
        LocalTime startTimeCohort = LocalTime.parse("00:00:00");
        LocalTime endTimeCohort = LocalTime.parse("23:59:59");
        if (cohortDetails.has("start_time") && cohortDetails.has("end_time")) {
            startTimeCohort = LocalTime.parse(cohortDetails.getString("start_time"));
            endTimeCohort   = LocalTime.parse(cohortDetails.getString("end_time"));
        }
        if (!(now.isAfter(startTimeCohort) & now.isBefore(endTimeCohort))) {
            return validity;
        }

        validity = true;
        return validity;
    }

    private static JSONObject getValueCalcParams(JSONObject params, JSONObject featuresObj, JSONObject singleOffer) {
        JSONObject extracted_value_calc_params = new JSONObject();
        extracted_value_calc_params.put("value_multiplier",1);
        extracted_value_calc_params.put("copcar_multiplier",1);
        extracted_value_calc_params.put("alpha_multiplier",1);
        extracted_value_calc_params.put("propensity_multiplier",1);
        extracted_value_calc_params.put("value_power",1);
        extracted_value_calc_params.put("copcar_power",1);
        extracted_value_calc_params.put("alpha_power",1);
        extracted_value_calc_params.put("propensity_power",1);

        try {
            //Get the parameters for the value calculation from the linked configurations
//            JSONArray value_calc_id_corpora = (JSONArray) ((
//                    (JSONObject) params.getJSONObject("preloadCorpora")
//                            .get("value_calc_id")).get("data"));
//            String value_calc_id = value_calc_id_corpora.getJSONObject(0).getString("value_calc_id");

            JSONObject value_calc_params_with_cruft = (JSONObject) params.getJSONObject("preloadCorpora")
                            .get("value_calc_params");
            JSONArray value_calc_params_array = value_calc_params_with_cruft.getJSONArray("data").getJSONObject(0).getJSONArray("value_calc_params");

            for (int i = 0; i < value_calc_params_array.length(); i++) {
                String value_calc_param_name = value_calc_params_array.getJSONObject(i).keys().next();
                JSONObject value_calc_params = value_calc_params_array.getJSONObject(i).getJSONObject(value_calc_param_name);

                if (value_calc_params.getString("type").equals("numeric")) {
                    double value_calc_param_value = DataTypeConversions.getDouble(value_calc_params, "value");
                    extracted_value_calc_params.put(value_calc_param_name,value_calc_param_value);
                }
                if (value_calc_params.getString("type").equals("offer_matrix_lookup")) {
                    String value_calc_key = value_calc_params.getString("value");
                    double value_calc_param_value = DataTypeConversions.getDouble(singleOffer, value_calc_key);
                    extracted_value_calc_params.put(value_calc_param_name,value_calc_param_value);
                }
                if (value_calc_params.getString("type").equals("parameter_from_data_source_lookup")) {
                    String value_calc_key = value_calc_params.getString("value");
                    double value_calc_param_value = DataTypeConversions.getDouble(featuresObj, value_calc_key);
                    extracted_value_calc_params.put(value_calc_param_name,value_calc_param_value);
                }
            }
        } catch (Exception e) {
            LOGGER.error("BusinessLogicValueCalc:E003: Parameters for value calculation not successfully extracted," +
                    " using 1 for all parameters. Error: " + e.getMessage());
        }
        return extracted_value_calc_params;
    }

    private static JSONObject populateErrorDefaults(JSONObject params, String error_message) {
        if (!error_message.isEmpty()) {
            LOGGER.error(error_message);
        }
        JSONObject business_logic_results = new JSONObject();
        if (params.has("business_logic_results")) {
            business_logic_results = params.getJSONObject("business_logic_results");
            business_logic_results.put("bundle_value", -999);
            business_logic_results.put("final_value", -999);
            business_logic_results.put("modified_offer_score", -999);
        } else {
            business_logic_results.put("bundle_value", -999);
            business_logic_results.put("final_value", -999);
            business_logic_results.put("modified_offer_score", -999);
        }
        params.put("business_logic_results", business_logic_results);
        return params;
    }

}
