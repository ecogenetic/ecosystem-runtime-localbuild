package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.algorithm.similarity.EmbeddingStoreSupport;
import com.ecosystem.algorithm.similarity.ItemEmbeddingCache;
import com.ecosystem.algorithm.similarity.SimilarityScorer;
import com.ecosystem.data.mongodb.MongoDBWorkerRead;
import com.ecosystem.utils.GlobalSettings;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import com.mongodb.client.MongoClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Pre-score loader for the LoRA / {@code lora} model type.
 *
 * Resolves exported user and item embedding vectors into {@code params} so
 * {@link PostScoreLoRA} can rank via {@link SimilarityScorer}. Reads
 * {@code predictor.lora.*} only — Two-Tower properties are untouched.
 *
 * Configure with {@code plugin.prescore=com.ecosystem.plugin.customer.PrePredictLoRA}.
 */
public class PrePredictLoRA extends PreScoreSuper {

    private static final Logger LOG = LogManager.getLogger(PrePredictLoRA.class.getName());

    static final String DEFAULT_EMBEDDINGS_DB = "logging";
    static final String USER_EMBEDDINGS_COLLECTION = "lora_user_embeddings";
    static final String ITEM_EMBEDDINGS_COLLECTION = "lora_item_embeddings";

    public PrePredictLoRA() throws Exception {
        mongoDBWorkerRead = new MongoDBWorkerRead();
    }

    public void getPrePredict() {
    }

    public static JSONObject getPrePredict(MongoClient mongoClient, JSONObject params, CqlSession session) throws IOException {
        try {
            if (mongoClient == null) {
                LOG.error("PrePredictLoRA:E002: no shared MongoClient; skipping embedding resolution.");
                return params;
            }

            GlobalSettings settings = getSettings(params);
            String embeddingsDb = EmbeddingStoreSupport.cleanOrDefault(
                    settings != null ? settings.getLoraEmbeddingDb() : null, DEFAULT_EMBEDDINGS_DB);
            String runId = EmbeddingStoreSupport.clean(settings != null ? settings.getLoraRunId() : null);
            String userCollection = EmbeddingStoreSupport.cleanOrDefault(
                    settings != null ? settings.getLoraUserCollection() : null, USER_EMBEDDINGS_COLLECTION);
            String itemCollection = EmbeddingStoreSupport.cleanOrDefault(
                    settings != null ? settings.getLoraItemCollection() : null, ITEM_EMBEDDINGS_COLLECTION);
            String customerKey = EmbeddingStoreSupport.cleanOrDefault(
                    settings != null ? settings.getLoraCustomerKey() : null, "customer_id");
            String offerKey = EmbeddingStoreSupport.cleanOrDefault(
                    settings != null ? settings.getLoraOfferKey() : null, "offer");

            float[] userVector = null;
            String customerId = resolveCustomerId(params, customerKey);
            if (customerId != null && !customerId.isEmpty()) {
                userVector = EmbeddingStoreSupport.loadUserEmbedding(
                        mongoClient, embeddingsDb, userCollection, runId, customerKey, customerId);
            }

            if (userVector == null) {
                String defaultCustomerId = PrePredictTwoTower.resolveDefaultCustomerId(settings, customerKey, customerId);
                if (defaultCustomerId != null) {
                    userVector = loadDefaultUserEmbedding(
                            mongoClient, embeddingsDb, userCollection, runId, customerKey, defaultCustomerId);
                    if (userVector != null) {
                        params.put("default_lookup", true);
                        LOG.warn("PrePredictLoRA:W003: no embedding for customer "
                                + PrePredictTwoTower.maskCustomerId(customerId)
                                + "; using default " + PrePredictTwoTower.maskCustomerId(defaultCustomerId));
                    }
                }
            }

            if (userVector != null) {
                params.put("user_embedding", EmbeddingStoreSupport.floatArrayToJson(userVector));
            } else {
                LOG.warn("PrePredictLoRA:W001: no user embedding for customer: "
                        + PrePredictTwoTower.maskCustomerId(customerId));
            }

            String cacheKey = ItemEmbeddingCache.buildKey(embeddingsDb, itemCollection, runId, offerKey);
            Map<String, float[]> itemMap = ItemEmbeddingCache.computeIfAbsent(cacheKey,
                    k -> EmbeddingStoreSupport.loadItemEmbeddingMap(
                            mongoClient, embeddingsDb, itemCollection, runId, offerKey, "PrePredictLoRA"));
            if (itemMap != null && !itemMap.isEmpty()) {
                params.put(SimilarityScorer.ITEM_EMBEDDING_KEY_REF, cacheKey);
            }

            String matrixLookupKey = PrePredictTwoTower.resolveOfferMatrixLookupKey(settings);
            if (matrixLookupKey != null && !matrixLookupKey.isEmpty()) {
                params.put(SimilarityScorer.OFFER_MATRIX_LOOKUP_KEY, matrixLookupKey);
            }

        } catch (Exception e) {
            LOG.error("PrePredictLoRA:E001: failed to resolve embeddings: "
                    + PrePredictTwoTower.stackTraceToString(e));
        }
        return params;
    }

    private static float[] loadDefaultUserEmbedding(MongoClient mongoClient, String db, String collectionName,
                                                    String runId, String customerKey, String defaultCustomerId) {
        String cacheKey = ItemEmbeddingCache.buildKey(db, collectionName, runId,
                "__default_user__|" + customerKey + "|" + defaultCustomerId);
        Map<String, float[]> cached = ItemEmbeddingCache.computeIfAbsent(cacheKey, k -> {
            float[] vector = EmbeddingStoreSupport.loadUserEmbedding(
                    mongoClient, db, collectionName, runId, customerKey, defaultCustomerId);
            if (vector == null) return null;
            Map<String, float[]> single = new HashMap<>();
            single.put(defaultCustomerId, vector);
            return single;
        });
        return cached != null ? cached.get(defaultCustomerId) : null;
    }

    private static String resolveCustomerId(JSONObject params, String customerKey) {
        try {
            if (params.has("lookup") && params.getJSONObject("lookup").has("value")) {
                return String.valueOf(params.getJSONObject("lookup").get("value"));
            }
        } catch (Exception ignore) {
        }
        try {
            if (params.has("customer")) return String.valueOf(params.get("customer"));
        } catch (Exception ignore) {
        }
        try {
            if (params.has("featuresObj") && params.getJSONObject("featuresObj").has(customerKey)) {
                return String.valueOf(params.getJSONObject("featuresObj").get(customerKey));
            }
        } catch (Exception ignore) {
        }
        return null;
    }
}
