package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.algorithm.similarity.ContextFeatureExtractor;
import com.ecosystem.algorithm.similarity.ContextFusionStrategy;
import com.ecosystem.algorithm.similarity.ContextTower;
import com.ecosystem.algorithm.similarity.ItemEmbeddingCache;
import com.ecosystem.algorithm.similarity.SimilarityScorer;
import com.ecosystem.data.mongodb.MongoDBWorkerRead;
import com.ecosystem.utils.GlobalSettings;
import com.ecosystem.worker.api.ApiModelClient;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import org.bson.Document;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-score loader for the two-tower / {@code similarity} model type.
 *
 * Resolves embedding vectors into {@code params} so {@link com.ecosystem.algorithm.similarity.SimilarityScorer}
 * (used by {@link PostScoreTwoTower}) can score offers without any model in the
 * hot path:
 * <ul>
 *   <li><b>User vector</b> — one indexed point read per request from the configured
 *       user embedding collection by {@code customer_id} (+ {@code run_id}); else,
 *       when {@code predictor.twotower.user.embed} is configured, fetched live from
 *       an {@code api:pytorch} sidecar via {@link ApiModelClient#embed}; else, the
 *       embedding of the default customer configured in {@code predictor.param.lookup}
 *       {@code lookup.value} (mirrors the platform's feature-store default lookup;
 *       stamps {@code params.default_lookup=true}). Placed in
 *       {@code params.user_embedding}. When {@code predictor.twotower.context.features}
 *       is non-blank, the user vector is fused with a request-time context embedding
 *       before being stored (classic two-tower when features are absent).</li>
 *   <li><b>Item vectors</b> — loaded once per {@code db|collection|run_id} with a
 *       single bulk query into the in-memory {@link ItemEmbeddingCache} and reused
 *       across requests; only the cache key rides in {@code params.item_embeddings_key}.
 *       The cache is invalidated only by {@code /refresh} (or a new run id).
 *       Item vectors may alternatively ride on {@code offerMatrix.embedding}.</li>
 * </ul>
 *
 * Uses the campaign's shared {@link MongoClient} passed by the plugin loader —
 * no connections or properties files are opened per request; connections are
 * re-established only at startup and {@code /refresh}.
 *
 * Configure with {@code plugin.prescore=com.ecosystem.plugin.customer.PrePredictTwoTower}.
 */
public class PrePredictTwoTower extends PreScoreSuper {

    private static final Logger LOG = LogManager.getLogger(PrePredictTwoTower.class.getName());

    /** Default database for the embedding collections (canonical: the workbench
     * embedding-export default target; generated properties always set it explicitly). */
    static final String DEFAULT_EMBEDDINGS_DB = "logging";
    static final String USER_EMBEDDINGS_COLLECTION = "two_tower_user_embeddings";
    static final String ITEM_EMBEDDINGS_COLLECTION = "two_tower_item_embeddings";

    public PrePredictTwoTower() throws Exception {
        mongoDBWorkerRead = new MongoDBWorkerRead();
    }

    /**
     * Pre-pre predict (no-arg hook used by the plugin loader).
     */
    public void getPrePredict() {
    }

    /**
     * Resolve user + item embeddings into params. Mutates params in place.
     *
     * @param mongoClient the campaign's shared MongoDB client (created at startup, rebuilt on /refresh)
     * @param params      runtime params (carries input/value features, lookup, in_params)
     * @param session     Cassandra session (unused; MongoDB is the embedding store)
     * @return params (mutated in place)
     */
    public static JSONObject getPrePredict(MongoClient mongoClient, JSONObject params, CqlSession session) throws IOException {
        try {
            if (mongoClient == null) {
                LOG.error("PrePredictTwoTower:E002: no shared MongoClient provided by the runtime; "
                        + "check mongo.connect in the campaign properties and run /refresh. Skipping embedding resolution.");
                return params;
            }

            GlobalSettings settings = getSettings(params);
            String embeddingsDb = resolveEmbeddingsDatabase(settings, params);
            String runId = clean(settings != null ? settings.getTwoTowerRunId() : null);
            String userCollection = cleanOrDefault(settings != null ? settings.getTwoTowerUserCollection() : null, USER_EMBEDDINGS_COLLECTION);
            String itemCollection = cleanOrDefault(settings != null ? settings.getTwoTowerItemCollection() : null, ITEM_EMBEDDINGS_COLLECTION);
            String customerKey = cleanOrDefault(settings != null ? settings.getTwoTowerCustomerKey() : null, "customer_id");
            String offerKey = cleanOrDefault(settings != null ? settings.getTwoTowerOfferKey() : null, "offer");

            /* 1. User embedding: single indexed point read by customer_id (+ run_id) */
            float[] userVector = null;
            String customerId = resolveCustomerId(params, customerKey);
            if (customerId != null && !customerId.isEmpty()) {
                userVector = loadUserEmbedding(mongoClient, embeddingsDb, userCollection, runId, customerKey, customerId);
            }

            /* 2. Fallback: live user embedding via api:pytorch sidecar */
            if (userVector == null) {
                userVector = embedUserViaApi(settings, params);
            }

            /* 3. Fallback: the default customer's embedding (mirrors the platform's
             * feature-store default lookup in StageParamsDB: default id from
             * predictor.param.lookup lookup.value unless it is the '123' sentinel,
             * else the customer id inside the configured default record).
             * Stamps default_lookup=true like the platform. */
            if (userVector == null) {
                String defaultCustomerId = resolveDefaultCustomerId(settings, customerKey, customerId);
                if (defaultCustomerId != null) {
                    userVector = loadDefaultUserEmbedding(mongoClient, embeddingsDb, userCollection, runId, customerKey, defaultCustomerId);
                    if (userVector != null) {
                        params.put("default_lookup", true);
                        LOG.warn("PrePredictTwoTower:W003: no embedding for customer " + maskCustomerId(customerId)
                                + "; using default customer embedding " + maskCustomerId(defaultCustomerId)
                                + " (from the configured default lookup)");
                    }
                }
            }

            if (userVector != null) {
                float[] toStore = userVector;
                if (settings != null && settings.hasContextTowerConfig()) {
                    try {
                        toStore = applyContextTower(userVector, params, settings);
                    } catch (Exception e) {
                        LOG.error("PrePredictTwoTower:C005: context tower failed; using unfused user embedding: "
                                + stackTraceToString(e));
                        toStore = userVector;
                    }
                }
                params.put("user_embedding", floatArrayToJson(toStore));
            } else {
                LOG.warn("PrePredictTwoTower:W001: no user embedding resolved for customer: " + maskCustomerId(customerId));
            }

            /* 4. Item embeddings: cached offer -> vector map, loaded once per
             * db|collection|runId|offerKey. computeIfAbsent guarantees a single bulk
             * load under concurrent misses; only non-empty loads are cached, so a
             * transient load failure is retried on the next request. The cache is
             * otherwise invalidated only by /refresh (or a new run id). */
            String cacheKey = ItemEmbeddingCache.buildKey(embeddingsDb, itemCollection, runId, offerKey);
            Map<String, float[]> itemMap = ItemEmbeddingCache.computeIfAbsent(cacheKey,
                    k -> loadItemEmbeddingMap(mongoClient, embeddingsDb, itemCollection, runId, offerKey));
            if (itemMap != null && !itemMap.isEmpty()) {
                params.put(SimilarityScorer.ITEM_EMBEDDING_KEY_REF, cacheKey);
            }

            /* Stamp Offer Lookup ID so SimilarityScorer joins matrix rows via
             * predictor.offer.matrix.key (e.g. product_id) to embedding cache values. */
            String matrixLookupKey = resolveOfferMatrixLookupKey(settings);
            if (matrixLookupKey != null && !matrixLookupKey.isEmpty()) {
                params.put(SimilarityScorer.OFFER_MATRIX_LOOKUP_KEY, matrixLookupKey);
            }

        } catch (Exception e) {
            LOG.error("PrePredictTwoTower:E001: failed to resolve embeddings: " + stackTraceToString(e));
        }
        return params;
    }

    /**
     * Encode request-time context features and fuse into the user embedding.
     * Soft-fails (returns {@code userVector} unchanged) on extract/dim/NaN issues;
     * stamps {@code params.context_embedding} only on success.
     */
    static float[] applyContextTower(float[] userVector, JSONObject params, GlobalSettings settings) {
        if (userVector == null) {
            LOG.debug("PrePredictTwoTower:C001: context features configured but userVector is null; skip fusion");
            return null;
        }
        double[] ctxRaw = ContextFeatureExtractor.extractAll(params, settings);
        if (ctxRaw == null || ctxRaw.length == 0 || ContextFeatureExtractor.isAllZero(ctxRaw)) {
            LOG.warn("PrePredictTwoTower:C002: context features configured but extract produced empty/zero vector; "
                    + "using unfused user embedding");
            return userVector;
        }

        ContextTower tower = new ContextTower(ctxRaw.length, userVector.length);
        float[] ctxEmb = tower.forward(ctxRaw);
        if (ctxEmb == null || ctxEmb.length != userVector.length) {
            LOG.warn("PrePredictTwoTower:C004: context embedding dim mismatch (ctx="
                    + (ctxEmb == null ? "null" : ctxEmb.length) + ", user=" + userVector.length
                    + "); using unfused user embedding");
            return userVector;
        }
        if (containsNaNOrInf(ctxEmb)) {
            LOG.error("PrePredictTwoTower:C005: context embedding contains NaN/Inf; using unfused user embedding");
            return userVector;
        }

        boolean[] usedUnknown = new boolean[]{false};
        String fusionType = settings != null ? settings.getContextFusionType() : null;
        ContextFusionStrategy fusion = ContextFusionStrategy.fromType(fusionType, usedUnknown);
        if (usedUnknown[0]) {
            LOG.warn("PrePredictTwoTower:C003: unknown fusion type '" + fusionType
                    + "'; falling back to additive");
        }

        float[] fused = fusion.fuse(userVector, ctxEmb);
        if (fused == null || fused.length != userVector.length || containsNaNOrInf(fused)) {
            LOG.error("PrePredictTwoTower:C005: fusion produced invalid vector; using unfused user embedding");
            return userVector;
        }

        if (params != null) {
            params.put("context_embedding", floatArrayToJson(ctxEmb));
        }
        double norm = l2Norm(ctxEmb);
        String effectiveFusion = usedUnknown[0] || fusionType == null || fusionType.isBlank()
                ? ContextFusionStrategy.TYPE_ADDITIVE
                : fusionType.trim().toLowerCase();
        LOG.info("PrePredictTwoTower:I010: context fusion applied type=" + effectiveFusion
                + " context_norm=" + String.format(java.util.Locale.ROOT, "%.4f", norm)
                + " features=" + settings.getContextTowerFeatures());
        return fused;
    }

    private static boolean containsNaNOrInf(float[] v) {
        if (v == null) return true;
        for (float x : v) {
            if (Float.isNaN(x) || Float.isInfinite(x)) return true;
        }
        return false;
    }

    private static double l2Norm(float[] v) {
        double s = 0.0;
        for (float x : v) s += (double) x * x;
        return Math.sqrt(s);
    }

    /** Render an exception + stack trace for the structured logger (no printStackTrace to stderr). */
    static String stackTraceToString(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /** PII-safe rendering of a customer id for log lines (keeps a short prefix for triage). */
    static String maskCustomerId(String customerId) {
        if (customerId == null || customerId.isEmpty()) return "(none)";
        if (customerId.length() <= 4) return customerId.charAt(0) + "***";
        return customerId.substring(0, 4) + "***(" + customerId.length() + ")";
    }

    static String resolveEmbeddingsDatabase(GlobalSettings settings, JSONObject params) {
        /* Use predictor.twotower.embedding.db only. Do not reuse predictor.param.lookup.db —
         * that is the feature-store database (often the campaign DB) and was incorrectly
         * stealing embedding reads away from the exported collection target. */
        return cleanOrDefault(settings != null ? settings.getTwoTowerEmbeddingDb() : null, DEFAULT_EMBEDDINGS_DB);
    }

    /**
     * Catalog id field from {@code predictor.offer.matrix} JSON {@code key}
     * (workbench Offer Lookup ID). Empty when unset.
     */
    static String resolveOfferMatrixLookupKey(GlobalSettings settings) {
        if (settings == null) return "";
        String raw = clean(settings.getPredictorOfferMatrix());
        if (raw == null || raw.isEmpty() || "{}".equals(raw)) return "";
        try {
            JSONObject matrix = new JSONObject(raw);
            return clean(matrix.optString("key", ""));
        } catch (Exception e) {
            LOG.warn("PrePredictTwoTower:W004: could not parse predictor.offer.matrix key: " + e.getMessage());
            return "";
        }
    }

    /**
     * Default customer id for the embedding fallback — the same "default lookup"
     * the platform uses when a customer is missing from the feature store
     * ({@code StageParamsDB}), checked in the same order:
     * <ol>
     *   <li>{@code predictor.param.lookup} {@code lookup.value}, unless it is the
     *       {@code 123} placeholder sentinel;</li>
     *   <li>the {@code default} object inside {@code predictor.param.lookup};</li>
     *   <li>{@code predictor.param.lookup.default} (Workbench Lookup Defaults).</li>
     * </ol>
     * For branches 2–3 the id is read from the default record using, in order:
     * the two-tower {@code customerKey}, the feature-store {@code lookup.key}
     * (Workbench Key Field, often {@code customer}), then aliases
     * {@code customer} / {@code customer_id}.
     * Returns {@code null} when no usable id is configured or the resolved id is
     * identical to the id already tried (a re-read would miss again).
     */
    static String resolveDefaultCustomerId(GlobalSettings settings, String customerKey, String alreadyTriedId) {
        String defaultValue = null;
        try {
            String lookup = settings != null ? settings.getPredictorParamLookup() : null;
            JSONObject paramLookup = (lookup != null && !lookup.isBlank()) ? new JSONObject(lookup) : null;
            String featureStoreLookupKey = resolveFeatureStoreLookupKey(paramLookup);

            /* Branch 1: default id configured directly on lookup.value */
            if (paramLookup != null && paramLookup.has("lookup")) {
                JSONObject lookupObj = paramLookup.optJSONObject("lookup");
                if (lookupObj != null && lookupObj.has("value")) {
                    String candidate = String.valueOf(lookupObj.get("value")).trim();
                    if (!candidate.isEmpty() && !candidate.equalsIgnoreCase("123")) {
                        defaultValue = candidate;
                    }
                }
            }

            /* Branch 2: default record embedded in predictor.param.lookup */
            if (defaultValue == null && paramLookup != null && paramLookup.has("default")) {
                defaultValue = extractCustomerIdFromDefaultRecord(
                        paramLookup.optJSONObject("default"), customerKey, featureStoreLookupKey);
            }

            /* Branch 3: default record in predictor.param.lookup.default */
            if (defaultValue == null && settings != null) {
                String lookupDefault = settings.getPredictorParamLookupDefault();
                if (lookupDefault != null && !lookupDefault.isBlank()) {
                    defaultValue = extractCustomerIdFromDefaultRecord(
                            new JSONObject(lookupDefault), customerKey, featureStoreLookupKey);
                }
            }
        } catch (Exception e) {
            LOG.warn("PrePredictTwoTower:resolveDefaultCustomerId: " + e.getMessage());
            return null;
        }
        if (defaultValue == null || defaultValue.isEmpty()) return null;
        if (alreadyTriedId != null && defaultValue.equalsIgnoreCase(alreadyTriedId.trim())) return null;
        return defaultValue;
    }

    /** Feature-store Key Field from {@code predictor.param.lookup.lookup.key}, or empty. */
    static String resolveFeatureStoreLookupKey(JSONObject paramLookup) {
        if (paramLookup == null || !paramLookup.has("lookup")) return "";
        JSONObject lookupObj = paramLookup.optJSONObject("lookup");
        if (lookupObj == null) return "";
        return clean(lookupObj.optString("key", ""));
    }

    /**
     * Customer id out of a default lookup record (e.g. Workbench Lookup Defaults
     * {@code {customer:'none'}} or {@code {customer_id:'CUS_1',...}}).
     * Tries {@code customerKey} (two-tower), then feature-store lookup key, then
     * {@code customer} / {@code customer_id}.
     */
    static String extractCustomerIdFromDefaultRecord(JSONObject defaultRecord, String customerKey,
                                                     String featureStoreLookupKey) {
        if (defaultRecord == null) return null;
        for (String key : defaultCustomerIdFieldCandidates(customerKey, featureStoreLookupKey)) {
            if (key == null || key.isEmpty() || !defaultRecord.has(key)) continue;
            String candidate = String.valueOf(defaultRecord.get(key)).trim();
            if (!candidate.isEmpty()) return candidate;
        }
        return null;
    }

    /** Ordered, deduplicated field names to probe on a Lookup Defaults object. */
    static String[] defaultCustomerIdFieldCandidates(String customerKey, String featureStoreLookupKey) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        if (customerKey != null && !customerKey.isBlank()) keys.add(customerKey.trim());
        if (featureStoreLookupKey != null && !featureStoreLookupKey.isBlank()) {
            keys.add(featureStoreLookupKey.trim());
        }
        keys.add("customer");
        keys.add("customer_id");
        return keys.toArray(new String[0]);
    }

    /**
     * Load (and cache) the default customer's embedding. Cached through
     * {@link ItemEmbeddingCache} under a dedicated key so the fallback costs one
     * Mongo read per deployment instead of one per cold request; misses are not
     * cached (a later export of the default customer's embedding is picked up
     * without a restart) and /refresh clears the cache.
     */
    private static float[] loadDefaultUserEmbedding(MongoClient mongoClient, String db, String collectionName,
                                                    String runId, String customerKey, String defaultCustomerId) {
        String cacheKey = ItemEmbeddingCache.buildKey(db, collectionName, runId,
                "__default_user__|" + customerKey + "|" + defaultCustomerId);
        Map<String, float[]> cached = ItemEmbeddingCache.computeIfAbsent(cacheKey, k -> {
            float[] vector = loadUserEmbedding(mongoClient, db, collectionName, runId, customerKey, defaultCustomerId);
            if (vector == null) {
                LOG.warn("PrePredictTwoTower:W004: default customer " + maskCustomerId(defaultCustomerId)
                        + " has no embedding in " + db + "." + collectionName
                        + (runId != null && !runId.isBlank() ? " run_id=" + runId : "")
                        + "; default fallback unavailable.");
                return null;
            }
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

    /**
     * Build a typed Mongo filter for a user embedding lookup. Never string-concat
     * JSON and {@code Document.parse}: ids containing quotes/braces previously
     * produced an empty filter and returned an arbitrary user's vector.
     */
    static Document buildUserEmbeddingFind(String customerKey, String customerId, String runId) {
        Document find = new Document(customerKey, customerId);
        if (runId != null && !runId.isBlank()) {
            find.append("run_id", runId);
        }
        return find;
    }

    @SuppressWarnings("unchecked")
    private static float[] loadUserEmbedding(MongoClient mongoClient, String db, String collectionName, String runId, String customerKey, String customerId) {
        try {
            MongoCollection<Document> collection = (MongoCollection<Document>) connectionFactory.connectMongoDB(mongoClient, db, collectionName);
            if (collection == null) return null;
            Document find = buildUserEmbeddingFind(customerKey, customerId, runId);
            Document doc = collection.find(find).limit(1).first();
            if (doc != null && doc.containsKey("embedding")) {
                return numberListToFloatArray(doc.get("embedding"));
            }
        } catch (Exception e) {
            LOG.warn("PrePredictTwoTower:loadUserEmbedding: " + e.getMessage());
        }
        return null;
    }

    /**
     * Bulk-load the offer -> embedding map for a run in one query. Called at most
     * once per cache key (single loader via {@link ItemEmbeddingCache#computeIfAbsent});
     * the result is reused across all requests until /refresh.
     *
     * <p>Requires a non-blank {@code predictor.twotower.run.id}: an empty filter
     * would load the entire collection (all runs) into heap.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, float[]> loadItemEmbeddingMap(MongoClient mongoClient, String db, String collectionName, String runId, String offerKey) {
        Map<String, float[]> map = new HashMap<>();
        if (runId == null || runId.isBlank()) {
            LOG.error("PrePredictTwoTower:E003: predictor.twotower.run.id is not set; refusing to bulk-load "
                    + db + "." + collectionName + " without a run_id filter (would load every run's embeddings)."
                    + " Set predictor.twotower.run.id in the deployment properties.");
            return map;
        }
        try {
            MongoCollection<Document> collection = (MongoCollection<Document>) connectionFactory.connectMongoDB(mongoClient, db, collectionName);
            if (collection == null) return map;

            long startMs = System.currentTimeMillis();
            Document find = new Document("run_id", runId);

            FindIterable<Document> docs = collection.find(find)
                    .projection(new Document(offerKey, 1).append("embedding", 1).append("_id", 0))
                    .batchSize(1000);
            int duplicates = 0;
            for (Document doc : docs) {
                Object offerValue = doc.get(offerKey);
                float[] vector = numberListToFloatArray(doc.get("embedding"));
                if (offerValue != null && vector != null) {
                    if (map.put(String.valueOf(offerValue), vector) != null) duplicates++;
                }
            }
            if (duplicates > 0) {
                LOG.warn("PrePredictTwoTower:W002: " + duplicates + " duplicate '" + offerKey
                        + "' keys in " + db + "." + collectionName + " run_id=" + runId
                        + "; last document wins. Check the export for duplicate offer ids.");
            }
            LOG.info("PrePredictTwoTower:I002: loaded " + map.size() + " item embeddings into cache for "
                    + db + "." + collectionName + " run_id=" + runId
                    + " in " + (System.currentTimeMillis() - startMs) + "ms"
                    + " (rebuilt only on /refresh or new run id)");
        } catch (Exception e) {
            LOG.warn("PrePredictTwoTower:loadItemEmbeddings: " + e.getMessage());
        }
        return map;
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    private static String cleanOrDefault(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned == null || cleaned.isBlank() ? fallback : cleaned;
    }

    private static float[] embedUserViaApi(GlobalSettings settings, JSONObject params) {
        try {
            String userEmbed = settings != null ? settings.getTwoTowerUserEmbed() : null;
            if (userEmbed == null || userEmbed.isBlank()) {
                return null;
            }
            ApiModelClient client = ApiModelClient.fromEntry("api:" + userEmbed, settings);
            if (client == null) {
                LOG.error("PrePredictTwoTower:embedUserViaApi: invalid predictor.twotower.user.embed: " + userEmbed);
                return null;
            }
            JSONArray input = params.optJSONArray("input");
            JSONArray value = params.optJSONArray("value");
            if (input == null || value == null) {
                LOG.warn("PrePredictTwoTower:embedUserViaApi: no input/value features in params");
                return null;
            }
            return client.embed(input, value);
        } catch (Exception e) {
            LOG.warn("PrePredictTwoTower:embedUserViaApi: " + e.getMessage());
            return null;
        }
    }

    private static JSONArray floatArrayToJson(float[] vector) {
        JSONArray arr = new JSONArray();
        for (float v : vector) {
            arr.put((double) v);
        }
        return arr;
    }

    /** Convert a Mongo driver List of numbers (or a JSONArray) to a float vector. */
    private static float[] numberListToFloatArray(Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) return null;
            float[] out = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (!(item instanceof Number)) return null;
                out[i] = ((Number) item).floatValue();
            }
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            if (arr.length() == 0) return null;
            float[] out = new float[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                out[i] = (float) arr.getDouble(i);
            }
            return out;
        }
        return null;
    }
}
