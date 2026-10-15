package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.algorithm.similarity.SimilarityScorer;
import com.ecosystem.utils.GlobalSettings;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import hex.genmodel.easy.EasyPredictModelWrapper;
import org.json.JSONArray;
import org.json.JSONObject;

import static com.ecosystem.EcosystemResponse.obtainBudget;

/**
 * LoRA post-score plugin. Thin wrapper that delegates ranking to
 * {@link SimilarityScorer} over embeddings resolved by {@link PrePredictLoRA}.
 *
 * Used with {@code predictor.model.type=lora}. Reads metric from
 * {@code predictor.lora.metric}.
 *
 * Configure with {@code plugin.postscore=com.ecosystem.plugin.customer.PostScoreLoRA}.
 */
public class PostScoreLoRA extends PostScoreSuper {

    private static final Logger LOGGER = LogManager.getLogger(PostScoreLoRA.class.getName());

    public PostScoreLoRA() {
    }

    public void getPostPredict() {
    }

    public static JSONObject getPostPredict(JSONObject predictModelMojoResult, JSONObject params,
                                            CqlSession session, EasyPredictModelWrapper[] models) {
        double startTime = System.nanoTime();
        String uuid = params.optString("uuid", "no-uuid");

        try {
            applyWhitelistResultCount(params);

            String metric = resolveMetric(params);
            predictModelMojoResult = SimilarityScorer.apply(predictModelMojoResult, params, metric,
                    PostScoreLoRA::additionalOfferChecks);

            applyBudgetSpendLimits(params, predictModelMojoResult);
        } catch (Exception e) {
            LOGGER.error("PostScoreLoRA:E001:" + uuid + " - " + PrePredictTwoTower.stackTraceToString(e));
        }

        if (!predictModelMojoResult.has("final_result")) {
            LOGGER.warn("PostScoreLoRA:W001:" + uuid
                    + " - SimilarityScorer produced no final_result; returning empty result set.");
            predictModelMojoResult.put("final_result", new JSONArray());
        }

        params.put("explore", 0);

        JSONObject result = PostScoreSuper.getTopScores(params, predictModelMojoResult);
        stampResponseExploreFlag(result);

        double endTime = System.nanoTime();
        JSONArray returnedOffers = result.optJSONArray("final_result");
        LOGGER.info("PostScoreLoRA:I001:" + uuid
                + " - offers returned: " + (returnedOffers != null ? returnedOffers.length() : 0)
                + " time in ms: " + ((endTime - startTime) / 1000000));

        return result;
    }

    private static void stampResponseExploreFlag(JSONObject result) {
        try {
            JSONArray finalResult = result.optJSONArray("final_result");
            if (finalResult == null) return;
            for (int i = 0; i < finalResult.length(); i++) {
                JSONObject row = finalResult.optJSONObject(i);
                if (row == null) continue;
                JSONObject full = row.optJSONObject("result_full");
                if (full != null && full.optInt("explore", 0) == 1) {
                    result.put("explore", 1);
                    return;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("PostScoreLoRA:stampResponseExploreFlag: " + e.getMessage());
        }
    }

    /**
     * Per-offer eligibility. Extend here for LoRA-specific behavior filters;
     * keep Two-Tower's PostScoreTwoTower unchanged.
     */
    public static boolean additionalOfferChecks(JSONObject singleOffer, JSONObject params) {
        if (!isOfferOnWhitelist(singleOffer, params)) return false;
        return true;
    }

    private static boolean isOfferOnWhitelist(JSONObject singleOffer, JSONObject params) {
        try {
            if (!params.has("whitelist") || params.getJSONObject("whitelist").isEmpty()) return true;
            Object configured = params.getJSONObject("whitelist").get("whitelist");

            java.util.List<String> whitelist = new java.util.ArrayList<>();
            if (configured instanceof JSONArray) {
                JSONArray arr = (JSONArray) configured;
                for (int i = 0; i < arr.length(); i++) whitelist.add(String.valueOf(arr.get(i)));
            } else if (configured instanceof java.util.List) {
                for (Object item : (java.util.List<?>) configured) whitelist.add(String.valueOf(item));
            }
            if (whitelist.isEmpty()) return true;

            String[] candidateKeys = {"offer_name_final", "offer_name", "offer", "offer_id"};
            for (String key : candidateKeys) {
                String candidate = singleOffer.optString(key, "").trim();
                if (candidate.isEmpty()) continue;
                for (String allowed : whitelist) {
                    if (candidate.equalsIgnoreCase(allowed)) return true;
                }
            }
            return false;
        } catch (Exception e) {
            LOGGER.warn("PostScoreLoRA:isOfferOnWhitelist: " + e.getMessage() + " (offer included)");
            return true;
        }
    }

    private static void applyBudgetSpendLimits(JSONObject params, JSONObject predictModelMojoResult) {
        try {
            if (getSettings(params).getPredictorOfferBudget() == null) return;
            JSONArray finalResult = predictModelMojoResult.optJSONArray("final_result");
            if (finalResult == null || finalResult.length() == 0) return;
            JSONObject featuresObj = params.optJSONObject("featuresObj");
            if (featuresObj == null) featuresObj = new JSONObject();

            for (int i = 0; i < finalResult.length(); i++) {
                JSONObject row = finalResult.optJSONObject(i);
                if (row == null) continue;
                JSONObject singleOffer = row.optJSONObject("offer_matrix");
                if (singleOffer == null) singleOffer = row;
                try {
                    JSONObject budgetItem = obtainBudget(singleOffer, featuresObj, 0.0);
                    row.put("spend_limit", budgetItem.getDouble("spend_limit"));
                } catch (Exception e) {
                    LOGGER.warn("PostScoreLoRA:applyBudgetSpendLimits: row " + i + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("PostScoreLoRA:applyBudgetSpendLimits: " + e.getMessage());
        }
    }

    private static void applyWhitelistResultCount(JSONObject params) {
        try {
            if (!params.has("whitelist") || params.getJSONObject("whitelist").isEmpty()) return;
            Object configured = params.getJSONObject("whitelist").get("whitelist");
            int size = 0;
            if (configured instanceof JSONArray) size = ((JSONArray) configured).length();
            else if (configured instanceof java.util.List) size = ((java.util.List<?>) configured).size();
            if (size > 0) params.put("resultcount", size);
        } catch (Exception e) {
            LOGGER.warn("PostScoreLoRA:applyWhitelistResultCount: " + e.getMessage());
        }
    }

    private static String resolveMetric(JSONObject params) {
        try {
            GlobalSettings settings = getSettings(params);
            String metric = settings != null ? settings.getLoraMetric() : null;
            if (metric != null && !metric.isBlank()) {
                return SimilarityScorer.normalizeMetric(metric);
            }
        } catch (Exception e) {
            LOGGER.warn("PostScoreLoRA:resolveMetric: " + e.getMessage());
        }
        return SimilarityScorer.METRIC_COSINE;
    }
}
