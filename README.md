# ecosystem.Ai Client Pulse Responder (Runtime)

A real-time and near-time prediction platform for enterprise behavioral analytics and recommendation systems. This Spring Boot application provides a configurable prediction engine that supports multiple machine learning algorithms, dynamic model selection, and customizable business logic through a plugin-based architecture.

**Concept, invoke/learn sequence, algorithm convergence, and post-score pairing:** [GUIDE.md](GUIDE.md). Plugin contracts and shipped class lists stay in this README.

## Overview

The ecosystem.Ai Runtime is a headless API-first platform that enables real-time scoring, recommendation generation, and behavioral tracking. It supports various ML algorithms including Multi-Armed Bandits (Thompson Sampling, Epsilon-Greedy), Naive Bayes, Q-Learning, and Neural Networks.

**Version:** 0.9.7.0  
**Build:** 2026-09.5343  
**Java Version:** 17+  
**Spring Boot Version:** 4.1.1

## Key Features

- **Real-time Scoring**: Perform predictions using pre-loaded MOJO models (H2O AutoML, XGBoost, GLM) or HTTP sidecar models (`api:` / `api-ref:` in `mojo.key`)
- **Offer matrix drivers**: MongoDB, Cassandra, REST, and Presto/Trino (Workbench `trino` uses the Presto JDBC worker at preload)
- **Dynamic Algorithm Selection**: Dynamic Engagement approaches (`binaryThompson` default, epsilonGreedy, naiveBayes, behaviorAlgos, Network, QLearning)
- **Two-Tower / similarity**: `predictor.model.type=similarity` with `PrePredictTwoTower` + `PostScoreTwoTower` (not a Dynamic Engagement `approach`)
- **LoRA**: `predictor.model.type=lora` with `PrePredictLoRA` + `PostScoreLoRA` and `predictor.lora.*` (not Two-Tower, not a DE `approach`)
- **Multi-campaign**: Multiple use cases on one instance (`POST /refresh`, `/campaigns`, `POST /learning`)
- **MCP**: In-process Model Context Protocol at `POST /mcp` (protocol 2026-07-28)
- **Ontology**: Runtime closed-loop vocabulary at `/ontology/runtime`
- **Runtime dashboard**: Static UI served from the Maven library (`/`)
- **Plugin Architecture**: Extensible pre-score and post-score logic through custom plugins
- **Multi-Armed Bandit (MAB)**: Built-in support for exploration/exploitation strategies
- **Feature Store Integration**: MongoDB-based feature store for customer data
- **Response Tracking**: Track recommendation acceptance and update models accordingly (`POST /response`, singular)
- **Business Logic Engine**: Customizable business rules and calculations
- **Property encryption**: `ENC(...)` values via Jasypt (`SecurePropertyResolver`)
- **RESTful API**: OpenAPI / Scalar documentation
- **Scheduled Processing**: `MultiCampaignScheduler` plus legacy rolling updates

## Architecture

The application follows a plugin-based architecture with clear separation of concerns:

```text
src/main/java/com/ecosystem/
├── runtime/           # Spring Boot entry (RuntimeApplication) and ProductMaster API overlay
├── plugin/
│   ├── business/     # Business logic plugins (value calculation, offer groups)
│   ├── customer/     # Customer scoring plugins (pre-score, post-score)
│   └── reward/       # Reward calculation plugins for reinforcement learning
```

MCP, ontology, dashboard static assets, scoring engine, and `ECOSYSTEM_ALGORITHMS.md` ship in the `com.ecosystem:ecosystem-runtime` Maven dependency, not as sources in this repo.

### Plugin Types

1. **Pre-Score Plugins** (`plugin.prescore`, see [Pre-Score Plugins](#pre-score-plugins)): Enrich `params` after the feature store and before scoring
  - `DefaultPreScore` / `PrePredictCustomer`: Passthrough templates
  - `PreScoreDynamic`: Contextual variables and virtual features
  - `PreScoreLookup`: Recent contact/response history on `params`
  - `PrePredictAutoDate`: Date-part features
  - `PrePredictTwoTower`: User/item embeddings for similarity
  - `PrePredictLoRA`: User/item embeddings for LoRA (`predictor.lora.*`)
2. **Post-Score Plugins** (`plugin.postscore`, see [Post-Score Plugins](#post-score-plugins)): Turn scores into `final_result` offers
  - `PlatformDynamicEngagement` / `PlatformDynamicEngagementProduct`: Dynamic Engagement (no MOJO)
  - `PostScoreRecommender`, `PostScoreBasic`, `PostScoreRecommenderOffers`: MOJO recommenders
  - `PostScoreNetwork` / `PostScoreNetworkSelector`: Network Selector (not PageRank `approach=Network`)
  - `PostScoreTwoTower`: Two-Tower / similarity ranking
  - `PostScoreLoRA`: LoRA ranking (`predictor.model.type=lora`)
  - `PostScoreSentimentalEquilibrium`: live `behaviorAlgos` + `sentimentalEquilibrium` (options-store `arm_reward`)
  - `PostScoreSentimentalEquilibriumPreScored`: Workbench SE algorithm job already on the feature store (`engagementScore`, `sentimental_equilibrium`, …)
3. **Business Logic Plugins** (`plugin.business.`*): Custom business rules
  - `BusinessLogic`: Main business logic router
  - `BusinessLogicValueCalc`: Value calculations
  - `BusinessLogicOfferGroups`: Offer grouping logic
4. **Reward Plugins** (`plugin.reward.`*): Shape scoring and learning for Dynamic Engagement (see [Reward Plugins](#reward-plugins))
  - `DefaultReward`: Binary accept/reject, no extra shaping (`reward=1.0`, `learning_reward=1.0`)
  - `ExampleReward`: Template with cost/state reward tiers — copy this to start
  - `BusinessLogicReward`: Delegates to business-logic functions via a `rewards_business_logic` corpus
  - `QLearnRewardPlugin`: Worked Q-Learning example with state/action payoffs

## Prerequisites

- **Java 17** or higher (this overlay compiles to Java 17 bytecode; platform production images use Amazon Corretto 25)
- **Maven 3.6+**
- **MongoDB** (for feature store and logging)
- **Cassandra** (optional, for whitelist and configuration)
- Access to ecosystem.Ai Maven repository: `https://maven.ecosystem.ai`

## Setup

### 1. Maven Configuration

Add the ecosystem.Ai repository to your `~/.m2/settings.xml`:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema/instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                  http://maven.apache.org/xsd/settings-1.0.0.xsd">
    <profiles>
        <profile>
            <id>default</id>
            <activation>
                <activeByDefault>true</activeByDefault>
            </activation>
            <repositories>
                <repository>
                    <id>ecosystem-repo</id>
                    <url>https://maven.ecosystem.ai</url>
                    <snapshots>
                        <updatePolicy>always</updatePolicy>
                    </snapshots>
                </repository>
            </repositories>
        </profile>
    </profiles>
</settings>
```

### 2. Build the Project

```bash
mvn clean package
```

This will create an executable JAR: `ecosystem-runtime-localbuild.jar`

### 3. Configuration

#### Main Configuration File: `ecosystem.properties`

Key configuration properties:

```properties
# Prediction case name
predictor.name=recommender

# MongoDB connection
mongo.connect=mongodb://user:password@localhost:54445/?authSource=admin
mongo.server=localhost
mongo.port=54445
mongo.ecosystem.user=ecosystem_user
mongo.ecosystem.password=EcoEco321

# Plugin configuration
plugin.prescore=com.ecosystem.plugin.customer.PrePredictCustomer
plugin.postscore=com.ecosystem.plugin.customer.PostScoreRecommender
plugin.reward=com.ecosystem.plugin.reward.DefaultReward

# Model configuration (MOJO files)
mojo.key=GLM_1_AutoML_20210818_132258.zip,XGBoost_1_AutoML_20210818_132258.zip

# Multi-Armed Bandit settings
predictor.epsilon=0.05
predictor.offercache=0

# Data paths
user.data=/data/
user.generated.models=/data/models/
```

#### Application Properties: `application.properties`

```properties
# Monitoring delay (in seconds, default: 600 = 10 minutes)
monitoring.delay=${monitoring_delay:600}

# Logging
logging.level.root=${logging:INFO}

# Properties file location
properties=${properties:ecosystem.properties}
```

### 4. Environment Variables

- `SERVER_PORT`: Server port (default: 8091)
- `monitoring_delay`: Monitoring delay in seconds
- `LOGGING_LEVEL_ROOT`: Root logging level
- `properties`: Path to ecosystem.properties file

### 5. Run the Application

```bash
java -jar ecosystem-runtime-localbuild.jar
```

Or with custom properties:

```bash
java -jar ecosystem-runtime-localbuild.jar --properties=/path/to/ecosystem.properties
```

## API Endpoints

The application exposes a RESTful API. OpenAPI UI is at `/swagger-ui.html`; Scalar at `/scalar.html`.

Closed loop: `POST /invocations` then `POST /response` (singular) with that row's `uuid` + `offer_name`. The `params` field on `/invocations` is a JSON **string**.

### Primary Endpoints

#### 1. **Invoke Prediction** - `POST /invocations`

Main endpoint for real-time scoring and recommendations.

**Request:**

```json
{
  "campaign": "recommender",
  "subcampaign": "none",
  "customer": "1234567890",
  "channel": "app",
  "numberoffers": 4,
  "userid": "test",
  "params": "{}"
}
```

**Response:** JSON with recommendations and scores

Optional `"debug":"true"` on the invoke request enables invocation debug traces.

#### 2. **Update Response** - `POST /response`

Track recommendation acceptance (async).

**Request:**

```json
{
  "uuid": "dcb54a23-0737-4768-845d-48162598c0f7",
  "offers_accepted": [{"offer_name": "OFFER_A"}],
  "channel_name": "app"
}
```

#### 3. **Refresh / multi-campaign** - `POST /refresh`

Load, reload, or delete a campaign on a running instance. Body includes `campaign` and `properties` (and action `load` / `delete` / `loadAll`).

#### 4. **Campaigns** - `GET /campaigns`, `GET /campaigns/{campaignId}`, `DELETE /campaigns/{campaignId}`

Also `GET /campaigns/persisted` and `GET /campaigns/execution-test`.

#### 5. **Learning** - `POST /learning`

Manually trigger Dynamic Interaction learning for `{ "campaign": "..." }`.

#### 6. **Validate properties** - `GET /validation/properties`

Query `campaignId` to check plugin, similarity/two-tower, and `api-ref` configuration.

#### 7. **MCP** - `POST /mcp`

Canonical in-process MCP (protocol 2026-07-28). Mutating tools require `X-API-KEY` / `MASTER_KEY`. `GET /mcp` and `POST /mcp/message` are deprecated legacy SSE.

#### 8. **Ontology** - `/ontology/runtime`

Concept catalog, closed-loop recipe, invoke/response validation, and TTL downloads.

#### 9. **Business Logic** - `POST /business`

Execute custom business logic calculations.

**Request:**

```json
{
  "business_logic": "value_calc",
  "business_logic_params": {...}
}
```

#### 10. **Predictor Response (Pre-loaded)** - `GET /predictorResponsePreLoad`

Perform prediction on pre-loaded model with database lookup.

**Query Parameters:**

- `valueJSON`: JSON string with prediction parameters
- `detail`: Response detail level (`none`, `basic`, or `all`)

**Example:**

```text
GET /predictorResponsePreLoad?valueJSON={"name":"predict1","mojo":"1","dbparam":true,"lookup":{"key":"customer","value":1234567890}}&detail=all
```

#### 11. **Offer Recommendations** - `GET /offerRecommendations`

Retrieve offer recommendations for a customer.

#### 12. **Update Offer Recommendations** - `PUT /offerRecommendations`

Update offer recommendations.

### API Authentication

The API supports API key authentication via the `X-API-KEY` header. Security can be configured in `RuntimeApplication.java`.

## Plugin Development

Plugins are loaded by reflection from `ecosystem.properties`. Method names and signatures must stay exact. Product differences belong in these overlay classes, not in `RuntimeApplication` or `ProductMaster`.

```text
POST /invocations
  → Pre-score  (plugin.prescore)   enrich params / features
  → Platform scoring               MOJO, Dynamic Engagement, or similarity
  → Post-score (plugin.postscore)  build final_result
POST /response
  → Reward     (plugin.reward)     reward and learning_reward
```

`plugin.prescore` and `plugin.postscore` are required. `plugin.reward` defaults to `DefaultReward` if omitted. `PreScoreSuper` and `PostScoreSuper` ship in the `com.ecosystem:ecosystem-runtime` JAR.

- Pre-score: [Pre-Score Plugins](#pre-score-plugins)
- Post-score: [Post-Score Plugins](#post-score-plugins)
- Reward: [Reward Plugins](#reward-plugins)

## Pre-Score Plugins

Pre-score plugins run on every `POST /invocations` **after** the feature store (and whitelist) are loaded and **before** the model or Dynamic Engagement scorer. They do not rank offers. They prepare the `params` object that scoring and post-score will read: contextual variables, extra features, dates, embeddings, or lookup history.

If pre-score fails to load, the runtime logs `Pre-score plugin not loaded — check plugin.prescore` and the campaign will not score.

### What you can change


| On `params`       | Typical use                                                                                |
| ----------------- | ------------------------------------------------------------------------------------------ |
| `in_params`       | Set `contextual_variable_one` / `contextual_variable_two` for Dynamic Engagement cells     |
| `featuresObj`     | Add or transform feature-store fields the model will score                                 |
| `input` / `value` | Keep in lockstep with `featuresObj` when you add features (`PrePredictAutoDate` does this) |
| New keys          | Carry lookup results to post-score (for example `prescore_data_lookup`)                    |


Do not remove `uuid`, `userid`, `lookup`, `resultcount`, or `explore`. Return `params` even on error (never null).

### Configure

```properties
plugin.prescore=com.ecosystem.plugin.customer.DefaultPreScore
```

Rebuild the localbuild JAR after changing Java. A properties-only class-name change is enough when you switch between classes that are already compiled.

### Which shipped class to use


| Class                | Use when                                                          | Notes                                                                                                                                    |
| -------------------- | ----------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `DefaultPreScore`    | You need a no-op or a class to copy                               | Extends `PreScoreSuper`. Safest starting point.                                                                                          |
| `PrePredictCustomer` | Same passthrough, without `PreScoreSuper` helpers                 | Empty template; prefer `DefaultPreScore` for new work.                                                                                   |
| `PreScoreDynamic`    | Contextual variables or virtual features come from a lookup table | Mongo signature. Set `predictor.param.lookup` with `dynamic_lookup`. Calls `getDynamicSettings` and `getPrepopulateContextualVariables`. |
| `PreScoreLookup`     | Post-score needs recent contacts and responses                    | Mongo signature. Writes `params.prescore_data_lookup` (`resultArrayContacts`, `resultArrayResponses`).                                   |
| `PrePredictAutoDate` | The model expects date parts (hour, day-of-week, weekend, …)      | Adds `datetime_*` fields to `featuresObj` and mirrors them onto `input` / `value`.                                                       |
| `PrePredictTwoTower` | `predictor.model.type=similarity`                                 | Mongo signature. Loads user/item embeddings for `PostScoreTwoTower`. Pair with that post-score class.                                    |
| `PrePredictLoRA`     | `predictor.model.type=lora`                                       | Mongo signature. Loads LoRA user/item embeddings (`predictor.lora.*`) for `PostScoreLoRA`. Do not mix with Two-Tower properties.          |


### Contract (custom Java)

The loader looks for a Mongo overload first, then the two-argument form:

```java
// With Mongo (PreScoreDynamic, PreScoreLookup, PrePredictTwoTower, PrePredictLoRA)
public static JSONObject getPrePredict(MongoClient mongoClient, JSONObject params, CqlSession session)

// Without Mongo (DefaultPreScore, PrePredictCustomer, PrePredictAutoDate)
public static JSONObject getPrePredict(JSONObject params, CqlSession session)
```

Also keep the empty loader hook `public void getPrePredict()`. Extend `PreScoreSuper` when you need its helpers (`getDynamicSettings`, `getPrepopulateContextualVariables`, logging lookups). Do not change the method name or parameter order.

```java
package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import org.json.JSONObject;

public class MyPreScore extends PreScoreSuper {

    public MyPreScore() {}

    public void getPrePredict() {}

    public static JSONObject getPrePredict(JSONObject params, CqlSession session) {
        if (params == null) {
            return new JSONObject();
        }
        JSONObject inParams = params.optJSONObject("in_params");
        if (inParams == null) {
            inParams = new JSONObject();
        }
        JSONObject featuresObj = params.optJSONObject("featuresObj");
        if (featuresObj != null && featuresObj.has("customer_segment")
                && !inParams.has("contextual_variable_one")) {
            inParams.put("contextual_variable_one", featuresObj.optString("customer_segment", ""));
        }
        params.put("in_params", inParams);
        return params;
    }
}
```

Then set:

```properties
plugin.prescore=com.ecosystem.plugin.customer.MyPreScore
```

Use `has()` / `optJSONObject` before reads. Wrap lookups in try-catch and return unmodified `params` on failure so scoring still runs.

### Typical uses

- **Bandit cells:** map a feature-store field onto `contextual_variable_one` so Thompson / epsilon-greedy partitions correctly.
- **Model features:** derive ratios, bins, or date parts before the MOJO sees the row.
- **Eligibility hints:** stash an offer list on `params` for post-score to filter.
- **Two-Tower:** always use `PrePredictTwoTower` with `PostScoreTwoTower`; do not mix with `PlatformDynamicEngagement`.
- **LoRA:** always use `PrePredictLoRA` with `PostScoreLoRA` and `predictor.lora.*`; do not mix with Two-Tower or `PlatformDynamicEngagement`.

## Post-Score Plugins

Post-score plugins run after platform scoring and **build the API response**. They turn model probabilities or Dynamic Engagement `arm_reward` values into `predictModelMojoResult.final_result` — the ranked offer list the caller reads from `POST /invocations`. This is where eligibility, pricing, product payload, and ranking live.

Reward plugins (next section) only multiply `arm_reward` and weight learning. They do not assemble `final_result`.

### What you must return

Each item in `final_result` needs at least:


| Field                  | Role                                                      |
| ---------------------- | --------------------------------------------------------- |
| `offer` / `offer_name` | Identity used later on `POST /response`                   |
| `score`                | Base score (model probability or `arm_reward`)            |
| `uuid`                 | Same uuid as the invoke, so `/response` can match the row |


Common extras: `modified_offer_score`, `offer_value`, `price`, `cost`, `alpha`, `beta`, `arm_reward`, `learning_reward`. Call `getTopScores(params, predictModelMojoResult)` before returning when using explore/exploit (skip only for Network Selector `lookup_passthrough`).

Never return null. On error, put an empty `final_result` array and return the input object.

### Configure

```properties
plugin.postscore=com.ecosystem.plugin.customer.PlatformDynamicEngagement
```

### Which shipped class to use

**Dynamic Engagement (no MOJO in the hot path)**


| Class                              | Use when                                                                                                 | Notes                                                                                                                           |
| ---------------------------------- | -------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `PlatformDynamicEngagement`        | `binaryThompson`, `epsilonGreedy`, `naiveBayes`, `QLearning`, `Network` (PageRank), most `behaviorAlgos` | Iterates Dynamic Engagement options, applies contextual-variable filter, sorts by `arm_reward`. Default for learning campaigns. |
| `PlatformDynamicEngagementProduct` | Same, but each offer should include a `product` object from the offer matrix                             | Same loop; adds product payload.                                                                                                |
| `PostScoreSentimentalEquilibrium`  | Live `behaviorAlgos` + `sub_approach=sentimentalEquilibrium`                                              | Ranks offers from options-store `arm_reward` (`score()` response-rate utility). Not the Workbench contact-rate job. |


**MOJO / model recommenders**


| Class                         | Use when                                                                        | Notes                                                      |
| ----------------------------- | ------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| `PostScoreBasic`              | Generic template: clustering, regression, multinomial, anomaly, Cox, embeddings | Copy this for a new model-backed campaign.                 |
| `PostScoreBasicOfferMatrix`   | Same as basic, with pricing from the offer matrix                               |                                                            |
| `PostScoreBasicParallel`      | Post-score needs an async/external runtime call                                 | Uses `ExternalRuntimeAsync`.                               |
| `PostScoreRecommender`        | Multiclass/binomial model whose labels are offers                               | Builds `final_result` from scored domains.                 |
| `PostScoreRecommenderOffers`  | One multiclass model + offer matrix (`offer_price`)                             | All offers in the matrix; model trained on `offer_name`.   |
| `PostScoreRecommenderMulti`   | One binomial MOJO **per product**                                               | Scores each offer-matrix row with its own in-memory model. |
| `PostScoreRecommenderProduct` | Product recommender that should carry product fields                            | Personality-series template.                               |


**Routing, similarity, domain examples**


| Class                          | Use when                                                                             | Notes                                                                            |
| ------------------------------ | ------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------- |
| `PostScoreNetwork`             | Network Selector: route this customer to another runtime by a feature (`switch_key`) | Needs `network` + `network_config` corpora. Nested hops use `in_params.ecosystem_network_path`. `experiment_selector` supports N-group allocations. **Not** PageRank `approach=Network`. |
| `PostScoreNetworkSelector`     | Network Selector: route by the **scored offer** (`type=model_selector`)              | Same corpora; `switch_key` is unused. Nested hops use `in_params.ecosystem_network_path`. |
| `PostScoreTwoTower`            | `predictor.model.type=similarity`                                                    | Pair with `PrePredictTwoTower`. Cosine/dot-product ranking.                      |
| `PostScoreLoRA`                | `predictor.model.type=lora`                                                          | Pair with `PrePredictLoRA`. Same ranking path as Two-Tower; properties are `predictor.lora.*`. |
| `PostScoreSpam`                | Spam vs ham classifier                                                               | Domain example; copy only if that is the use case.                               |
| `PostScoreSpendingPersonality` | Spend Personality via REST                                                           | Domain example; not a generic recommender.                                       |
| `PostScoreSentimentalEquilibriumPreScored` | Workbench `/sentimental-equilibrium` (or Process) already wrote SE fields onto the feature store | Same pattern as spend personality: copy `engagementScore` → `score`, `sentimental_equilibrium` → `offer`. Do **not** pair with live `behaviorAlgos`. |


### Contract (custom Java)

```java
public static JSONObject getPostPredict(
    JSONObject predictModelMojoResult,
    JSONObject params,
    CqlSession session,
    EasyPredictModelWrapper[] models)
```

Also keep `public void getPostPredict()`. Extend `PostScoreSuper` for `getOptions`, `getOptionsParams`, `getLocations`, `compareContextualVariableValues`, `generateOptionsSequence`, `getTopScores`, and `defaultOffer`.

```java
package com.ecosystem.plugin.customer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.ecosystem.utils.JSONArraySort;
import hex.genmodel.easy.EasyPredictModelWrapper;
import org.json.JSONArray;
import org.json.JSONObject;

public class MyPostScore extends PostScoreSuper {

    public MyPostScore() {}

    public void getPostPredict() {}

    public static JSONObject getPostPredict(
            JSONObject predictModelMojoResult,
            JSONObject params,
            CqlSession session,
            EasyPredictModelWrapper[] models) {
        if (predictModelMojoResult == null) {
            return new JSONObject();
        }
        if (params == null) {
            return predictModelMojoResult;
        }
        try {
            JSONArray options = getOptions(params);
            JSONArray finalOffers = new JSONArray();
            if (options != null) {
                for (int i = 0; i < options.length() && i < params.optInt("resultcount", 1); i++) {
                    JSONObject option = options.optJSONObject(i);
                    if (option == null) continue;
                    String offer = option.optString("optionKey", "");
                    if (offer.isEmpty()) continue;
                    JSONObject row = new JSONObject();
                    row.put("offer", offer);
                    row.put("offer_name", option.optString("option", offer));
                    row.put("score", option.optDouble("arm_reward", 0.0));
                    row.put("uuid", params.optString("uuid", ""));
                    finalOffers.put(row);
                }
            }
            predictModelMojoResult.put("final_result",
                    JSONArraySort.sortArray(finalOffers, "score", "double", "d"));
            return getTopScores(params, predictModelMojoResult);
        } catch (Exception e) {
            if (!predictModelMojoResult.has("final_result")) {
                predictModelMojoResult.put("final_result", new JSONArray());
            }
            return predictModelMojoResult;
        }
    }
}
```

Then set:

```properties
plugin.postscore=com.ecosystem.plugin.customer.MyPostScore
```

For Dynamic Engagement, copy `PlatformDynamicEngagement` rather than this stub: you need `compareContextualVariableValues`, MAB fields (`alpha`, `beta`, `arm_reward`), and sort by `arm_reward`. For a single MOJO, copy `PostScoreBasic`.

### Typical uses

- **Learning recommender:** `PlatformDynamicEngagement` + `DefaultReward` (or a custom reward). Close the loop with `POST /response`.
- **Supervised model:** `PostScoreBasic` or `PostScoreRecommender`; rank by model probability, optionally multiply by margin.
- **Two-Tower:** `PrePredictTwoTower` + `PostScoreTwoTower`; not a Dynamic Engagement `approach`.
- **LoRA:** `PrePredictLoRA` + `PostScoreLoRA` + `predictor.model.type=lora`; not Two-Tower and not a DE `approach`.
- **Route traffic:** `PostScoreNetwork` / `PostScoreNetworkSelector` to another campaign URL. Do not confuse with `approach=Network` (PageRank on co-presented offers).
- **Eligibility and price:** filter with `eligible_offers` or the offer matrix; set `offer_value` / `cost` before `getTopScores`.

## Reward Plugins

Reward plugins are the localbuild overlay for **how much an outcome is worth**. Pre-score and post-score plugins prepare features and rank offers on `POST /invocations`. The reward plugin is loaded from `plugin.reward` and is invoked by the Dynamic Engagement rolling loop: once per option at **score time**, and again when the campaign **learns** from `POST /response`.

If `plugin.reward` is omitted, the runtime defaults to `com.ecosystem.plugin.reward.DefaultReward`. `RewardSuper` ships in the `com.ecosystem:ecosystem-runtime` JAR; you only overlay the class named in properties.

Do not confuse this with the platform's internal `RewardStrategy` (Thompson sampling, epsilon-greedy formulas). Those stay in the Maven library. `plugin.reward` is the customer-owned shaping layer.

### What the two values do


| Field                    | When it applies                               | Effect                                                                                                                        |
| ------------------------ | --------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `reward`                 | Score time (`POST /invocations`)              | Multiplies the algorithm propensity: `arm_reward = propensity * reward`. Higher values rank that option higher.               |
| `learning_reward`        | Learning (`POST /response` + rolling process) | Weights that historical interaction. Larger values move α/β (or the Q-table) more.                                            |
| `learning_for_contacts`  | Learning                                      | Optional. If `true`, presentations (contacts) are weighted by `learning_reward`. Default `false`.                             |
| `learning_for_responses` | Learning                                      | Optional. If `true`, accepts (`POST /response`) are weighted by `learning_reward`. `BusinessLogicReward` sets this to `true`. |


`DefaultReward` leaves both multipliers at `1.0`, so ranking and learning stay as the algorithm computed them. Use a custom plugin when a conversion is not worth the same as every other conversion — for example margin, cost, VIP, or a sequential Q-Learning payoff.

Learning only happens if the caller closes the loop: `POST /invocations` then `POST /response` with that row's `uuid` + `offer_name`. Skipping `/response` means the campaign never trains, so `learning_reward` has no effect.

Supported Dynamic Engagement approaches: Ecosystem Rewards (`binaryThompson`), Epsilon-Greedy, Naive Bayes (`Bayesian Probabilistic`), Network, behavioral algorithms, and **Q-Learning** (`QLearning` requires a real `plugin.reward`, not the default 1.0).

### Configure

```properties
plugin.reward=com.ecosystem.plugin.reward.DefaultReward
```

Rebuild the localbuild JAR after changing Java. A properties-only class-name change is enough when you switch between classes that are already compiled.

### Which shipped class to use


| Class                 | Use when                                                                  | Notes                                                                                                                                         |
| --------------------- | ------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `DefaultReward`       | Binary take-up (accept = 1, ignore = 0) is enough                         | Safest starting point. No corpora required.                                                                                                   |
| `ExampleReward`       | You need a Java template to copy                                          | Replace the placeholder `cost` / `category` / `state` reads with feature-store, offer-matrix, or corpora values.                              |
| `BusinessLogicReward` | Rewards should be configurable without a new Java class                   | Requires additional corpora named `rewards_business_logic` (Mongo key `type`). Falls back to `1.0` / `1.0` and logs if the corpus is missing. |
| `QLearnRewardPlugin`  | `randomisation.approach=QLearning` and payoffs depend on state and action | Worked example (telecom-style `copcar` / `previous_day_mbs`). Copy and adapt; do not point a generic recommender at this class.               |


### `BusinessLogicReward` corpus

Link an additional corpus named `rewards_business_logic` with `key=type`. Each document names a `BusinessLogic` function and which result field to use:

```json
{
  "function_name": "value_calc",
  "type": "reward",
  "output": "reward_value"
}
```

```json
{
  "function_name": "value_calc",
  "type": "learning_reward",
  "output": "learning_value"
}
```

Implement the named function in `com.ecosystem.plugin.business.BusinessLogic` (or a class it dispatches to). `BusinessLogicReward` calls `BusinessLogic.getValues(params)` and reads `params.business_logic_results.<output>`.

### Contract (custom Java)

The runtime loads the class by reflection. Signatures must stay exact:

1. `extends RewardSuper`
2. `public static void reward()` — empty loader hook
3. `public static JSONObject reward(JSONObject params)` — calculate and return `params`
4. Always write a `rewards` object onto `params` (never return null; never omit `params.put("rewards", rewards)`)

```java
package com.ecosystem.plugin.reward;

import org.json.JSONObject;

public class MyReward extends RewardSuper {

    public MyReward() {}

    public static void reward() {}

    public static JSONObject reward(JSONObject params) {
        double reward = 1.0;
        double learning_reward = 1.0;

        // params.optJSONObject("optionsDoc")       — option currently being scored
        // params.optJSONObject("featuresObj")      — customer lookup
        // params.optJSONObject("preloadCorpora")   — additional corpora
        // params.optJSONObject("offerMatrixWithKey")
        // params.opt("dataArrayFinal")             — options already scored this invoke

        JSONObject rewards = params.optJSONObject("rewards");
        if (rewards == null) {
            rewards = new JSONObject();
        }
        rewards.put("reward", reward);
        rewards.put("learning_reward", learning_reward);
        params.put("rewards", rewards);
        return params;
    }
}
```

Then set:

```properties
plugin.reward=com.ecosystem.plugin.reward.MyReward
```

Keep `reward` and `learning_reward` as finite numbers. `NaN` or missing keys cause the rolling process to fall back to `1.0`. Prefer `optJSONObject` / `has()` over unchecked `get`. On error, log and return the default `1.0` values so scoring still completes.

### Typical uses

- **Margin or cost:** multiply `reward` by `(offer_value - offer_cost)` so high-margin offers rank above cheap take-up.
- **Segment weighting:** VIP or high-risk customers get a larger `learning_reward` so their accepts move the model faster.
- **Asymmetric learning:** set `learning_for_contacts` / `learning_for_responses` when presentations and accepts should not count equally.
- **Q-Learning journeys:** return a state/action payoff so the next offer depends on what was accepted before. Use `QLearnRewardPlugin` as the pattern, with Lookup Parameters plus `learning_rate`, `discount_factor`, and `random_action`.

## Scheduling Engine

The application includes a continuous scheduling engine that:

- Monitors configuration changes
- Processes dynamic engagement models
- Updates rolling statistics (Naive Bayes, Behavior, Network, Q-Learning)
- Refreshes model parameters

Configuration is controlled via `monitoring.delay` in `application.properties`.

## Data Storage

### MongoDB

Used for:

- Feature store (`ecosystem_meta` collection)
- Training data
- Logging (`ecosystemruntime` collection)
- Response tracking (`ecosystemruntime_response` collection)
- Dynamic corpora configuration

### Cassandra (Optional)

Used for:

- Whitelist lookups
- High-performance configuration storage

## Model Management

### Supported Model Formats

- **H2O MOJO**: AutoML models (GLM, XGBoost, etc.)
- **ZIP archives**: Multiple models can be packaged together

### Model Loading

Models are specified in `ecosystem.properties`:

```properties
mojo.key=model1.zip,model2.zip
```

Models should be placed in the directory specified by `user.generated.models`.

## Multi-Armed Bandit (MAB)

The platform supports exploration/exploitation strategies:

- **Epsilon-Greedy**: Configurable via `predictor.epsilon`
- **Thompson Sampling**: Binary Thompson sampling for binary rewards
- **Cache Duration**: Control offer caching via `predictor.offercache`

## Logging

Logging is configured via `application.properties`:

```properties
logging.level.root=INFO
logging.level.org.mongodb=warn
```

Logs are also stored in MongoDB in the `logging` database.

## Monitoring

Health and metrics endpoints are available at:

- `/health` - Application health
- `/metrics` - Application metrics
- `/info` - Application information

## Development

### Project Structure

```text
ecosystem-runtime-localbuild/
├── pom.xml                    # Maven configuration
├── ecosystem.properties       # Main configuration
├── plugin.properties          # Plugin metadata
├── cassandra.conf            # Cassandra driver configuration
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/ecosystem/
│   │   │       ├── runtime/   # Core application
│   │   │       └── plugin/    # Plugin implementations
│   │   └── resources/
│   │       ├── application.properties
│   │       └── application.yml
│   └── test/
└── README.md
```

### Running Tests

```bash
mvn test
```

## Troubleshooting

### Common Issues

1. **MongoDB Connection Failed**
  - Verify MongoDB is running
  - Check connection string in `ecosystem.properties`
  - Verify network connectivity
2. **Model Not Found**
  - Ensure MOJO files are in `user.generated.models` directory
  - Verify `mojo.key` in `ecosystem.properties` matches file names
3. **Plugin Not Loading**
  - Verify class name in `ecosystem.properties`
  - Check classpath includes plugin classes
  - Verify plugin extends correct base class
4. **Port Already in Use**
  - Change `SERVER_PORT` environment variable
  - Or modify port in application configuration

## License

ecosystem.Ai 1.0 - See [https://ecosystem.Ai](https://ecosystem.Ai) for details

## Support

- **Documentation**: [https://developer.ecosystem.ai](https://developer.ecosystem.ai)
- **Website**: [https://ecosystem.Ai](https://ecosystem.Ai)
- **API Documentation**: Available at `/swagger-ui.html` when running

## Contributing

**Note**: Please do not alter the POM file without checking with an ecosystem.Ai specialist.

When contributing:

1. Follow the plugin architecture patterns
2. Keep business logic in plugin classes, not in controllers
3. Ensure all external calls are properly handled with resilience patterns
4. Update configuration documentation for new features

---

**Built with Spring Boot 4.1.1 | Java 17+ | ecosystem.Ai Platform 0.9.7.0 (build 2026-09.5343)**