package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.algorithm.similarity.SimilarityScorer;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import hex.genmodel.easy.EasyPredictModelWrapper;
import org.json.JSONArray;
import org.json.JSONObject;

import static com.ecosystem.EcosystemResponse.obtainBudget;

/**
 * Two-tower post-score plugin. Thin wrapper that delegates ranking to the shared
 * {@link SimilarityScorer} (cosine / dot product over embeddings) and then runs
 * {@link PostScoreSuper#getTopScores}.
 *
 * Used with the {@code similarity} model type — the orchestrator bypasses H2O and
 * dynamic scoring and stamps {@code predictModelMojoResult.type="similarity"};
 * {@link PrePredictTwoTower} resolves the user/item embeddings into params.
 *
 * Configure with {@code plugin.postscore=com.ecosystem.plugin.customer.PostScoreTwoTower}.
 */
public class PostScoreTwoTower extends PostScoreSuper {

    private static final Logger LOGGER = LogManager.getLogger(PostScoreTwoTower.class.getName());

    public PostScoreTwoTower() {
    }

    public void getPostPredict() {
    }

    /**
     * @param predictModelMojoResult score result (stamped type="similarity")
     * @param params                 runtime params (carries embeddings + offerMatrix)
     * @param session                Cassandra session (unused here)
     * @param models                 preloaded H2O models (unused in similarity mode)
     * @return ranked result for further post-scoring
     */
    public static JSONObject getPostPredict(JSONObject predictModelMojoResult, JSONObject params, CqlSession session, EasyPredictModelWrapper[] models) {
        double startTime = System.nanoTime();
        String uuid = params.optString("uuid", "no-uuid");

        try {
            /* Whitelist: cap result count to the configured list size (mirrors other plugins);
             * offers are then filtered to the whitelist inside additionalOfferChecks. */
            applyWhitelistResultCount(params);

            String metric = resolveMetric(params);
            logEntryDiagnostics(uuid, predictModelMojoResult, params, metric);

            /* SimilarityScorer.apply performs epsilon slot-level exploration mixing
             * (params.mab.epsilon from predictor.epsilon): each top slot has probability
             * epsilon of being swapped for a random offer outside the top-N, including
             * cold-start offers without embeddings. */
            predictModelMojoResult = SimilarityScorer.apply(predictModelMojoResult, params, metric,
                    PostScoreTwoTower::additionalOfferChecks);

            applyBudgetSpendLimits(params, predictModelMojoResult);

            logScoringOutcome(uuid, predictModelMojoResult);
        } catch (Exception e) {
            LOGGER.error("PostScoreTwoTower:E001:" + uuid + " - " + PrePredictTwoTower.stackTraceToString(e));
        }

        if (!predictModelMojoResult.has("final_result")) {
            LOGGER.warn("PostScoreTwoTower:W001:" + uuid
                    + " - SimilarityScorer produced no final_result; returning empty result set.");
            predictModelMojoResult.put("final_result", new JSONArray());
        }

        /* Slot-level mixing supersedes the platform's request-level explore: force the
         * exploit path in getTopScores so the mixed ordering is preserved (a request-level
         * explore=1 would re-randomize the whole response). */
        params.put("explore", 0);

        JSONObject result = PostScoreSuper.getTopScores(params, predictModelMojoResult);

        stampResponseExploreFlag(result);

        double endTime = System.nanoTime();
        JSONArray returnedOffers = result.optJSONArray("final_result");
        LOGGER.info("PostScoreTwoTower:I001:" + uuid
                + " - offers returned: " + (returnedOffers != null ? returnedOffers.length() : 0)
                + " time in ms: " + ((endTime - startTime) / 1000000));

        return result;
    }

    /**
     * Response-level explore flag for logging/analytics parity with other plugins:
     * 1 when any returned slot was filled by epsilon exploration (per-row explore
     * stamps are set by {@link SimilarityScorer#applyEpsilonSlotMixing}).
     */
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
            LOGGER.warn("PostScoreTwoTower:stampResponseExploreFlag: " + e.getMessage());
        }
    }

    /**
     * Debug snapshot of every input the similarity scorer depends on. Enable
     * DEBUG logging for this class to trace why a request returns no offers:
     * missing user embedding (pre-score lookup failed), empty item embedding
     * map (wrong run id / collection), or an empty offer matrix.
     */
    private static void logEntryDiagnostics(String uuid, JSONObject predictModelMojoResult, JSONObject params, String metric) {
        if (!isDebugLogging()) return;

        try {
            String stampedType = predictModelMojoResult != null
                    ? predictModelMojoResult.optString("type", "<missing>")
                    : "<null result>";

            int userEmbeddingDims = -1;
            float[] userEmbedding = SimilarityScorer.extractUserEmbedding(params, predictModelMojoResult);
            if (userEmbedding != null) userEmbeddingDims = userEmbedding.length;

            int itemEmbeddingCount = -1;
            if (params.has(SimilarityScorer.ITEM_EMBEDDING_KEY_REF)) {
                java.util.Map<String, float[]> cached = com.ecosystem.algorithm.similarity.ItemEmbeddingCache
                        .get(params.optString(SimilarityScorer.ITEM_EMBEDDING_KEY_REF, null));
                if (cached != null) itemEmbeddingCount = cached.size();
            }
            if (itemEmbeddingCount < 0) {
                JSONObject itemEmbeddings = params.optJSONObject(SimilarityScorer.ITEM_EMBEDDING_MAP_KEY);
                if (itemEmbeddings != null) itemEmbeddingCount = itemEmbeddings.length();
            }

            JSONArray offerMatrix = params.optJSONArray("offerMatrix");
            int offerMatrixCount = offerMatrix != null ? offerMatrix.length() : -1;

            LOGGER.debug("PostScoreTwoTower:D001:" + uuid
                    + " - type=" + stampedType
                    + " metric=" + metric
                    + " explore=" + params.optInt("explore", -1)
                    + " resultcount=" + params.optInt("resultcount", -1)
                    + " user_embedding_dims=" + userEmbeddingDims
                    + " item_embeddings=" + itemEmbeddingCount
                    + " offerMatrix=" + offerMatrixCount);

            if (userEmbedding == null)
                LOGGER.debug("PostScoreTwoTower:D001a:" + uuid
                        + " - user_embedding missing: check PrePredictTwoTower lookup (customer key / user collection / run id).");
            if (itemEmbeddingCount <= 0)
                LOGGER.debug("PostScoreTwoTower:D001b:" + uuid
                        + " - item_embeddings empty: check item collection and predictor.twotower.run.id.");
            if (offerMatrixCount <= 0)
                LOGGER.debug("PostScoreTwoTower:D001c:" + uuid
                        + " - offerMatrix empty: check predictor.offer.matrix collection and MongoDB connectivity.");
        } catch (Exception e) {
            LOGGER.debug("PostScoreTwoTower:D001x:" + uuid + " - diagnostics failed: " + e.getMessage());
        }
    }

    /** Debug summary of the ranked result before getTopScores trims it. */
    private static void logScoringOutcome(String uuid, JSONObject predictModelMojoResult) {
        if (!isDebugLogging()) return;

        try {
            JSONArray finalResult = predictModelMojoResult.optJSONArray("final_result");
            int scored = finalResult != null ? finalResult.length() : 0;
            if (finalResult != null && scored > 0) {
                JSONObject top = finalResult.getJSONObject(0);
                LOGGER.debug("PostScoreTwoTower:D002:" + uuid
                        + " - scored offers: " + scored
                        + " top offer=" + top.optString("offer", "<none>")
                        + " top score=" + top.optDouble("score", Double.NaN));
            } else {
                LOGGER.debug("PostScoreTwoTower:D002:" + uuid
                        + " - scored offers: 0 (all offers skipped or inputs missing; see D001a-c above)");
            }
        } catch (Exception e) {
            LOGGER.debug("PostScoreTwoTower:D002x:" + uuid + " - diagnostics failed: " + e.getMessage());
        }
    }

    /**
     * Per-offer eligibility checks, applied before similarity scoring.
     *
     * ============================= USER EXTENSION POINT =============================
     * Add product-specific eligibility rules here (mirrors the eligibility-check
     * sections in PlatformDynamicEngagement / PostScoreBasicOfferMatrix). Examples:
     *
     *   // Only offer to customers on a compatible plan:
     *   // JSONObject featuresObj = params.getJSONObject("featuresObj");
     *   // if (!singleOffer.optString("plan_type").equalsIgnoreCase(featuresObj.optString("plan_type"))) return false;
     *
     *   // Exclude expired offers:
     *   // if (singleOffer.has("valid_to") && isExpired(singleOffer.getString("valid_to"))) return false;
     *
     * Return {@code false} to exclude the offer from scoring; {@code true} to keep it.
     * ================================================================================
     *
     * @param singleOffer one row of the offer matrix
     * @param params      runtime params (featuresObj, in_params, whitelist, ...)
     * @return whether the offer is eligible for scoring
     */
    public static boolean additionalOfferChecks(JSONObject singleOffer, JSONObject params) {
        /* If whitelist settings then only allow offers on list */
        if (!isOfferOnWhitelist(singleOffer, params)) return false;

        /* Add custom eligibility rules above this line. */
        return true;
    }

    /**
     * When a whitelist is configured on the request, only offers on the list are
     * eligible (matched against offer_name_final / offer_name / offer / offer_id,
     * case-insensitive). No whitelist means all offers pass.
     */
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
            LOGGER.warn("PostScoreTwoTower:isOfferOnWhitelist: " + e.getMessage() + " (offer included)");
            return true;
        }
    }

    /**
     * When predictor.offer.budget is configured, stamp each scored row with its
     * spend_limit via obtainBudget (rows without spend_limit would otherwise be
     * dropped by the budget branch in getSelectedPredictResultExploreExploit).
     */
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
                    LOGGER.warn("PostScoreTwoTower:applyBudgetSpendLimits: row " + i + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("PostScoreTwoTower:applyBudgetSpendLimits: " + e.getMessage());
        }
    }

    private static void applyWhitelistResultCount(JSONObject params) {
        try {
            if (params.has("whitelist") && !params.getJSONObject("whitelist").isEmpty()) {
                Object configuredWhitelist = params.getJSONObject("whitelist").get("whitelist");
                if (configuredWhitelist instanceof JSONArray) {
                    params.put("resultcount", ((JSONArray) configuredWhitelist).length());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("PostScoreTwoTower:applyWhitelistResultCount: " + e.getMessage());
        }
    }

    /** The shared Logger wrapper exposes the level as a public field rather than isDebugEnabled(). */
    private static boolean isDebugLogging() {
        return LOGGER.LOGGING_LEVEL_NUMBER >= Logger.DEBUG;
    }

    /**
     * Similarity metric precedence: per-request {@code in_params.metric}, then the
     * deployment property {@code predictor.twotower.metric} (emitted by the
     * workbench from the run's embedding_export.metric), then cosine. Unknown
     * values are normalized (warn + cosine) by {@link SimilarityScorer#normalizeMetric}.
     */
    private static String resolveMetric(JSONObject params) {
        try {
            if (params.has("in_params")) {
                JSONObject inParams = params.optJSONObject("in_params");
                if (inParams != null && inParams.has("metric")) {
                    String m = inParams.optString("metric", "").trim();
                    if (!m.isEmpty()) return SimilarityScorer.normalizeMetric(m);
                }
            }
        } catch (Exception ignore) {
        }
        try {
            String m = getSettings(params).getTwoTowerMetric();
            if (m != null && !m.isBlank()) return SimilarityScorer.normalizeMetric(m);
        } catch (Exception ignore) {
        }
        return SimilarityScorer.METRIC_COSINE;
    }
}
