# Ecosystem.AI Runtime Plugin System - Developer Guide

## Table of Contents
1. [Overview](#overview)
2. [Understanding the Plugin Architecture](#understanding-the-plugin-architecture)
3. [The Scoring Pipeline Flow](#the-scoring-pipeline-flow)
4. [Pre-Score Plugins: Preparing Data](#pre-score-plugins-preparing-data)
5. [Post-Score Plugins: Processing Results](#post-score-plugins-processing-results)
6. [Dynamic Data Loading Concepts](#dynamic-data-loading-concepts)
7. [Customizing Scores, Values, and Pricing](#customizing-scores-values-and-pricing)
8. [Understanding Data Structures](#understanding-data-structures)
9. [Common Use Cases and Patterns](#common-use-cases-and-patterns)
10. [Best Practices](#best-practices)
11. [Code Safety and Preventing Breakage](#code-safety-and-preventing-breakage)

---

## Overview

The Ecosystem.AI Runtime uses a **plugin-based architecture** that allows you to customize the scoring and recommendation engine without modifying core platform code. This design enables:

- **Extensibility**: Add custom business logic, scoring algorithms, and data transformations
- **Maintainability**: Keep platform code stable while allowing product-specific customizations
- **Flexibility**: Support multiple products/tenants with different business rules
- **Testability**: Isolate custom logic in plugins for easier testing

### Key Concept: The Contract

The plugin system works through **strict contracts** - method signatures and data structures that must be followed exactly. The runtime uses **reflection** to dynamically load your plugins at startup, which means:

- ✅ **Method names must match exactly** (`getPrePredict`, `getPostPredict`)
- ✅ **Parameter types and order must match exactly**
- ✅ **Return types must match exactly** (always `JSONObject`)
- ✅ **Required data structures must be preserved**

Think of it like implementing an interface - you must follow the contract, but you have complete freedom in how you implement the logic inside.

---

## Understanding the Plugin Architecture

### The Two-Phase Plugin System

The scoring pipeline has two main extension points:

```
┌─────────────────────────────────────────────────────────────┐
│                    API Request                               │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  PRE-SCORE PLUGIN                                           │
│  • Transform input data                                      │
│  • Add contextual variables                                  │
│  • Modify features                                           │
│  • Prepare data for scoring                                  │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  MODEL SCORING (Platform)                                    │
│  • Load features                                             │
│  • Execute ML model                                          │
│  • Generate predictions                                      │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  POST-SCORE PLUGIN                                           │
│  • Process model outputs                                     │
│  • Adjust scores with business logic                          │
│  • Filter and rank offers                                    │
│  • Apply pricing rules                                       │
│  • Generate final recommendations                            │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                    API Response                               │
└─────────────────────────────────────────────────────────────┘
```

### Why Two Phases?

**Pre-Score Phase** is about **preparation**:
- You don't have model results yet
- You're working with raw input data and features
- You can influence what gets scored and how
- Example: "If customer is VIP, add premium offers to the candidate set"

**Post-Score Phase** is about **refinement**:
- You have model predictions/scores
- You can adjust, filter, and rank based on business rules
- You can apply pricing, eligibility, and other constraints
- Example: "Boost scores for high-margin offers by 20%"

---

## The Scoring Pipeline Flow

### Step-by-Step Data Flow

1. **Request Arrives** → API receives request with customer ID, channel, etc.

2. **Feature Store Lookup** → Platform loads customer features from MongoDB
   - Historical behavior
   - Demographics
   - Product usage
   - Calculated features

3. **Pre-Score Plugin Executes** → Your custom logic runs
   - Receives: `params` (contains features, input params, configuration)
   - Can modify: `params.in_params`, `params.featuresObj`
   - Returns: Modified `params`

4. **Dynamic Data Loading** → Platform loads:
   - **Options**: Available offers/arms (for Multi-Armed Bandit)
   - **Option Params**: Configuration for contextual variables, randomization
   - **Locations**: Eligibility data (store hours, availability)
   - **Offer Matrix**: Pricing, costs, offer details

5. **Model Scoring** → Platform executes ML model
   - Uses features from `featuresObj`
   - Generates predictions/probabilities
   - Creates `predictModelMojoResult`

6. **Post-Score Plugin Executes** → Your custom logic runs
   - Receives: `predictModelMojoResult` (model outputs) + `params` (original input)
   - Can modify: Scores, pricing, offer selection, ranking
   - Must create: `final_result` array with offer objects
   - Returns: Modified `predictModelMojoResult`

7. **Response** → Platform formats and returns final recommendations

### Data Carriers: params and predictModelMojoResult

**`params`** is the **input carrier**:
- Travels through the entire pipeline
- Contains everything needed for scoring
- Modified by pre-score plugin
- Passed to post-score plugin for reference

**`predictModelMojoResult`** is the **output carrier**:
- Created after model scoring
- Contains model predictions
- Modified by post-score plugin
- Contains the final result that gets returned

---

## Pre-Score Plugins: Preparing Data

### Purpose and Responsibilities

Pre-score plugins run **before** the model scores. Their job is to:
- Prepare and transform input data
- Add contextual information
- Modify features based on business rules
- Set up eligibility criteria

### When to Use Pre-Score Plugins

Use pre-score plugins when you need to:

1. **Add Contextual Variables**
   ```java
   // Example: Set contextual variables based on customer segment
   if (featuresObj.has("customer_segment")) {
       in_params.put("contextual_variable_one", featuresObj.getString("customer_segment"));
   }
   ```

2. **Transform Features**
   ```java
   // Example: Create derived features
   if (featuresObj.has("total_revenue") && featuresObj.has("tenure_months")) {
       double avg_monthly_revenue = featuresObj.getDouble("total_revenue") / 
                                   featuresObj.getInt("tenure_months");
       featuresObj.put("avg_monthly_revenue", avg_monthly_revenue);
   }
   ```

3. **Apply Eligibility Rules**
   ```java
   // Example: Filter out ineligible offers before scoring
   if (featuresObj.has("account_status") && 
       featuresObj.getString("account_status").equals("SUSPENDED")) {
       in_params.put("eligible_offers", new JSONObject()); // Empty = no offers
   }
   ```

4. **Modify Input Parameters**
   ```java
   // Example: Adjust result count based on channel
   if (params.getString("channel").equals("mobile_app")) {
       params.put("resultcount", 5); // Show more offers in app
   }
   ```

### The Pre-Score Contract

```java
public static JSONObject getPrePredict(JSONObject params, CqlSession session) {
    // Your logic here
    
    // MUST return params (modified or unmodified)
    return params;
}
```

**Key Rules:**
- ✅ Always return `params` (never null)
- ✅ Preserve required keys (`uuid`, `userid`, `lookup`, etc.)
- ✅ Use `has()` before `get()` for optional fields
- ✅ Handle errors gracefully (return unmodified params on error)

### Example: Adding Contextual Variables

```java
public static JSONObject getPrePredict(JSONObject params, CqlSession session) {
    try {
        JSONObject in_params = params.getJSONObject("in_params");
        JSONObject featuresObj = params.getJSONObject("featuresObj");
        
        // If contextual variable not provided via API, derive from features
        if (!in_params.has("contextual_variable_one")) {
            if (featuresObj.has("customer_segment")) {
                String segment = featuresObj.getString("customer_segment");
                in_params.put("contextual_variable_one", segment);
            } else {
                in_params.put("contextual_variable_one", "DEFAULT");
            }
        }
        
        // Add second contextual variable based on spending
        if (!in_params.has("contextual_variable_two")) {
            if (featuresObj.has("total_revenue_l30d")) {
                double revenue = featuresObj.getDouble("total_revenue_l30d");
                String tier = revenue > 1000 ? "HIGH" : revenue > 500 ? "MEDIUM" : "LOW";
                in_params.put("contextual_variable_two", tier);
            }
        }
        
        params.put("in_params", in_params);
    } catch (Exception e) {
        LOGGER.error("PreScoreCustom:E001:UUID: " + params.get("uuid") + 
                    " Error: " + e.getMessage());
        // Return unmodified params on error
    }
    
    return params;
}
```

---

## Post-Score Plugins: Processing Results

### Purpose and Responsibilities

Post-score plugins run **after** the model scores. Their job is to:
- Process model predictions
- Apply business logic to scores
- Adjust pricing and values
- Filter and rank offers
- Generate final recommendations

### When to Use Post-Score Plugins

Use post-score plugins when you need to:

1. **Adjust Scores with Business Logic**
   ```java
   // Example: Boost scores for high-margin offers
   double margin = offer_value - offer_cost;
   double adjusted_score = base_score * (1 + (margin / offer_value) * 0.2);
   ```

2. **Apply Pricing Rules**
   ```java
   // Example: Apply dynamic pricing based on customer segment
   if (featuresObj.getString("customer_segment").equals("VIP")) {
       offer_value = offer_value * 0.9; // 10% discount for VIP
   }
   ```

3. **Filter Offers**
   ```java
   // Example: Remove offers below minimum score threshold
   if (score < 0.3) {
       continue; // Skip this offer
   }
   ```

4. **Rank and Sort**
   ```java
   // Example: Sort by modified score, then by margin
   JSONArray sorted = JSONArraySort.sortArray(finalOffers, 
                                               "modified_offer_score", 
                                               "double", "d");
   ```

### The Post-Score Contract

```java
public static JSONObject getPostPredict(
    JSONObject predictModelMojoResult,  // Model outputs
    JSONObject params,                  // Original input (for reference)
    CqlSession session,                 // Database session
    EasyPredictModelWrapper[] models    // Preloaded models (optional)
) {
    // Your logic here
    
    // MUST create final_result array
    predictModelMojoResult.put("final_result", finalOffersArray);
    
    // MUST call getTopScores if using MAB
    predictModelMojoResult = getTopScores(params, predictModelMojoResult);
    
    // MUST return predictModelMojoResult
    return predictModelMojoResult;
}
```

**Key Rules:**
- ✅ Always return `predictModelMojoResult` (never null)
- ✅ Must create `final_result` as JSONArray
- ✅ Must call `getTopScores()` if using Multi-Armed Bandit
- ✅ Preserve `featuresObj` structure
- ✅ Each offer in `final_result` must have: `offer`, `score`, `offer_name`

### Example: Adjusting Scores with Business Logic

```java
public static JSONObject getPostPredict(
    JSONObject predictModelMojoResult, 
    JSONObject params, 
    CqlSession session, 
    EasyPredictModelWrapper[] models
) {
    try {
        JSONObject featuresObj = predictModelMojoResult.getJSONObject("featuresObj");
        JSONObject offerMatrixWithKey = params.getJSONObject("offerMatrixWithKey");
        JSONArray finalOffers = new JSONArray();
        
        // Get dynamic options (available offers)
        JSONArray options = getOptions(params);
        
        int offerIndex = 0;
        for (int i = 0; i < options.length() && i < params.getInt("resultcount"); i++) {
            JSONObject option = options.getJSONObject(i);
            String offerKey = option.getString("optionKey");
            
            // Get base score from option (MAB arm reward)
            double baseScore = option.getDouble("arm_reward");
            
            // Get pricing from offer matrix
            double offerValue = 1.0;
            double offerCost = 1.0;
            if (offerMatrixWithKey.has(offerKey)) {
                JSONObject offer = offerMatrixWithKey.getJSONObject(offerKey);
                offerValue = offer.getDouble("price");
                offerCost = offer.getDouble("cost");
            }
            
            // Apply business logic: boost score by margin percentage
            double margin = offerValue - offerCost;
            double marginPercent = margin / offerValue;
            double adjustedScore = baseScore * (1 + marginPercent * 0.3); // 30% boost
            
            // Create offer object
            JSONObject offerObj = new JSONObject();
            offerObj.put("offer", offerKey);
            offerObj.put("offer_name", option.getString("option"));
            offerObj.put("score", baseScore);
            offerObj.put("modified_offer_score", adjustedScore);
            offerObj.put("final_score", adjustedScore);
            offerObj.put("offer_value", offerValue);
            offerObj.put("price", offerValue);
            offerObj.put("cost", offerCost);
            offerObj.put("uuid", params.get("uuid"));
            
            finalOffers.put(offerIndex++, offerObj);
        }
        
        // Sort by modified score
        JSONArray sorted = JSONArraySort.sortArray(finalOffers, 
                                                   "modified_offer_score", 
                                                   "double", "d");
        predictModelMojoResult.put("final_result", sorted);
        
        // Apply MAB top scores (explore/exploit)
        predictModelMojoResult = getTopScores(params, predictModelMojoResult);
        
    } catch (Exception e) {
        LOGGER.error("PostScoreCustom:E001:UUID: " + params.get("uuid") + 
                    " Error: " + e.getMessage());
    }
    
    return predictModelMojoResult;
}
```

---

## Dynamic Data Loading Concepts

### Understanding Dynamic Data

The platform uses a **dynamic data loading system** that allows real-time updates without code changes. This is crucial for:
- **A/B Testing**: Change offer configurations without redeploying
- **Real-Time Optimization**: Update Multi-Armed Bandit parameters as data comes in
- **Business Agility**: Adjust pricing, eligibility, and offers on the fly

### The Four Dynamic Data Sources

#### 1. Options (Dynamic Corpus)

**What it is**: The set of available offers/arms for Multi-Armed Bandit algorithms.

**How to access**:
```java
JSONArray options = getOptions(params);
```

**Structure**: Array of option objects, each containing:
- `optionKey`: Unique identifier for the offer
- `alpha`, `beta`: Thompson Sampling parameters
- `arm_reward`: Current reward estimate
- `option`: Display name/description
- `weighting`: Optional weighting factor

**Use case**: Iterate through available offers to score and rank them.

#### 2. Option Params (Configuration)

**What it is**: Configuration that controls how options are processed, including contextual variables and randomization settings.

**How to access**:
```java
JSONObject optionParams = getOptionsParams(params);
JSONObject contextual_variables = optionParams.getJSONObject("contextual_variables");
JSONObject randomisation = optionParams.getJSONObject("randomisation");
```

**Structure**:
```json
{
  "contextual_variables": {
    "contextual_variable_one_name": "customer_segment",
    "contextual_variable_two_name": "spending_tier"
  },
  "randomisation": {
    "epsilon": 0.1,
    "method": "thompson_sampling"
  }
}
```

**Use case**: Determine which features to use for contextual bandits, control exploration vs exploitation.

#### 3. Locations (Eligibility Data)

**What it is**: Data about offer availability, store hours, geographic eligibility, etc.

**How to access**:
```java
JSONObject locations = getLocations(params);
```

**Structure**: Nested objects keyed by offer ID, containing:
- `open_times`: Store hours by day
- `operatingStatus`: Whether location is operating
- Geographic constraints, etc.

**Use case**: Filter offers based on availability, location, or time-based rules.

#### 4. Offer Matrix (Pricing & Details)

**What it is**: Pricing, costs, and detailed information for each offer.

**How to access**:
```java
JSONObject offerMatrixWithKey = params.getJSONObject("offerMatrixWithKey");
```

**Structure**: Objects keyed by offer ID, containing:
- `price` or `offer_price`: Customer-facing price
- `cost` or `offer_cost`: Cost to business
- `description`: Offer details
- Custom fields as needed

**Use case**: Calculate margins, apply pricing rules, display offer details.

### Standard Pattern for Dynamic Engagement

Here's the typical pattern used in dynamic engagement plugins:

```java
// 1. Access all dynamic data sources
JSONArray options = getOptions(params);
JSONObject optionParams = getOptionsParams(params);
JSONObject locations = getLocations(params);
JSONObject offerMatrixWithKey = params.getJSONObject("offerMatrixWithKey");

// 2. Extract configuration
JSONObject contextual_variables = optionParams.getJSONObject("contextual_variables");
JSONObject randomisation = optionParams.getJSONObject("randomisation");

// 3. Get contextual variable values (from API or feature store)
JSONObject work = params.getJSONObject("in_params");
JSONObject featuresObj = predictModelMojoResult.getJSONObject("featuresObj");

// API takes preference over feature store
if (!work.has("contextual_variable_one")) {
    String featureName = contextual_variables.getString("contextual_variable_one_name");
    if (featuresObj.has(featureName)) {
        work.put("contextual_variable_one", featuresObj.get(featureName));
    }
}

// 4. Process each option
for (int j : optionsSequence) {
    JSONObject option = options.getJSONObject(j);
    String offerKey = option.getString("optionKey");
    
    // Check eligibility (locations, time, etc.)
    if (locations != null && locations.has(offerKey)) {
        // Apply eligibility rules
        if (!isEligible(locations.getJSONObject(offerKey), work)) {
            continue; // Skip this offer
        }
    }
    
    // Get pricing from offer matrix
    JSONObject offerDetails = offerMatrixWithKey.getJSONObject(offerKey);
    double price = offerDetails.getDouble("price");
    double cost = offerDetails.getDouble("cost");
    
    // Get base score (from MAB)
    double baseScore = option.getDouble("arm_reward");
    
    // Apply business logic
    double adjustedScore = calculateAdjustedScore(baseScore, price, cost, featuresObj);
    
    // Create offer object and add to results
    // ...
}
```

### Why This Matters

The dynamic data loading system means:
- **No code changes needed** to add/remove offers
- **Real-time updates** to pricing and eligibility
- **A/B testing** by changing configurations
- **Multi-tenant support** with different configurations per product

---

## Customizing Scores, Values, and Pricing

### Understanding Score Types

In the post-score phase, you work with multiple score types:

1. **Base Score** (`score`): Raw score from the model or MAB algorithm
2. **Modified Score** (`modified_offer_score`): Score after applying business logic
3. **Final Score** (`final_score`): Score used for final ranking

### Score Modification Patterns

#### Pattern 1: Margin-Based Adjustment

Boost scores for high-margin offers:

```java
double margin = offerValue - offerCost;
double marginPercent = margin / offerValue;
double adjustedScore = baseScore * (1 + marginPercent * boostFactor);
```

#### Pattern 2: Customer Segment Adjustment

Different scoring rules per segment:

```java
String segment = featuresObj.getString("customer_segment");
double multiplier = 1.0;

switch (segment) {
    case "VIP":
        multiplier = 1.2; // 20% boost for VIP
        break;
    case "PREMIUM":
        multiplier = 1.1; // 10% boost for Premium
        break;
    default:
        multiplier = 1.0;
}

double adjustedScore = baseScore * multiplier;
```

#### Pattern 3: Recency and Frequency

Boost scores based on customer behavior:

```java
int daysSinceLastPurchase = featuresObj.getInt("days_snc_lst_pch");
int purchaseFrequency = featuresObj.getInt("purchase_frequency");

// Boost for recent customers
double recencyBoost = daysSinceLastPurchase < 7 ? 1.15 : 1.0;

// Boost for frequent customers
double frequencyBoost = purchaseFrequency > 10 ? 1.1 : 1.0;

double adjustedScore = baseScore * recencyBoost * frequencyBoost;
```

#### Pattern 4: Competitive Pricing

Adjust scores based on competitive positioning:

```java
double marketPrice = getMarketPrice(offerKey);
double priceRatio = offerValue / marketPrice;

if (priceRatio < 0.9) {
    // 10%+ discount - boost score
    adjustedScore = baseScore * 1.2;
} else if (priceRatio > 1.1) {
    // 10%+ premium - reduce score
    adjustedScore = baseScore * 0.8;
} else {
    adjustedScore = baseScore;
}
```

### Pricing Modification Patterns

#### Pattern 1: Segment-Based Pricing

```java
String segment = featuresObj.getString("customer_segment");
double basePrice = offerMatrix.getDouble("price");

double finalPrice = basePrice;
switch (segment) {
    case "VIP":
        finalPrice = basePrice * 0.9; // 10% discount
        break;
    case "LOYAL":
        finalPrice = basePrice * 0.95; // 5% discount
        break;
}
```

#### Pattern 2: Volume-Based Pricing

```java
int purchaseHistory = featuresObj.getInt("total_purchases");
double basePrice = offerMatrix.getDouble("price");

if (purchaseHistory > 50) {
    finalPrice = basePrice * 0.85; // 15% discount for high-volume
} else if (purchaseHistory > 20) {
    finalPrice = basePrice * 0.9; // 10% discount for medium-volume
}
```

#### Pattern 3: Time-Based Pricing

```java
String dayOfWeek = params.getJSONObject("in_params").getString("day");
double basePrice = offerMatrix.getDouble("price");

// Weekend pricing
if (dayOfWeek.equals("saturday") || dayOfWeek.equals("sunday")) {
    finalPrice = basePrice * 1.1; // 10% premium on weekends
}
```

### Complete Example: Business Logic Scoring

```java
private double calculateBusinessScore(
    double baseScore,
    double offerValue,
    double offerCost,
    JSONObject featuresObj,
    JSONObject params
) {
    // 1. Margin-based boost
    double margin = offerValue - offerCost;
    double marginPercent = margin / offerValue;
    double marginBoost = 1 + (marginPercent * 0.3); // 30% of margin as boost
    
    // 2. Customer segment boost
    String segment = featuresObj.optString("customer_segment", "STANDARD");
    double segmentMultiplier = getSegmentMultiplier(segment);
    
    // 3. Recency boost
    int daysSinceLastPurchase = featuresObj.optInt("days_snc_lst_pch", 999);
    double recencyBoost = daysSinceLastPurchase < 30 ? 1.1 : 1.0;
    
    // 4. Channel adjustment
    String channel = params.optString("channel", "web");
    double channelMultiplier = channel.equals("mobile_app") ? 1.05 : 1.0;
    
    // Combine all factors
    double finalScore = baseScore * marginBoost * segmentMultiplier * 
                       recencyBoost * channelMultiplier;
    
    return finalScore;
}
```

---

## Understanding Data Structures

### The params Object

The `params` object is your primary data carrier through the pipeline. Key sections:

```java
{
  // Request metadata
  "uuid": "request-unique-id",
  "userid": "system-user",
  "channel": "mobile_app",
  
  // Input parameters (from API)
  "in_params": {
    "contextual_variable_one": "VIP",
    "contextual_variable_two": "HIGH_SPENDER",
    "eligible_offers": { ... }
  },
  
  // Feature store data (customer features)
  "featuresObj": {
    "customer_segment": "VIP",
    "total_revenue_l30d": 1500.0,
    "days_snc_lst_pch": 5,
    // ... hundreds of features
  },
  
  // Configuration
  "resultcount": 5,
  "explore": 0.1,
  "mab": {
    "epsilon": 0.1,
    "class": "thompson_sampling"
  },
  
  // Dynamic data (loaded by platform)
  "offerMatrixWithKey": {
    "OFFER_1": { "price": 100, "cost": 50 },
    "OFFER_2": { "price": 200, "cost": 120 }
  }
}
```

### The predictModelMojoResult Object

Created after model scoring, contains predictions and results:

```java
{
  // Features used for scoring (same as params.featuresObj)
  "featuresObj": { ... },
  
  // Model outputs
  "type": ["multinomial"],
  "probability": [0.85],
  "label": ["OFFER_1"],
  "domainsProbabilityObj": {
    "OFFER_1": 0.85,
    "OFFER_2": 0.10,
    "OFFER_3": 0.05
  },
  
  // Final result (created by post-score plugin)
  "final_result": [
    {
      "offer": "OFFER_1",
      "offer_name": "Premium Package",
      "score": 0.85,
      "modified_offer_score": 0.92,
      "final_score": 0.92,
      "offer_value": 100.0,
      "price": 100.0,
      "cost": 50.0,
      "uuid": "..."
    }
  ]
}
```

### The Offer Object (in final_result)

Each offer in the final result must have these minimum fields:

**Required Fields:**
- `offer`: String identifier (e.g., "OFFER_1")
- `offer_name`: String display name (e.g., "Premium Package")
- `score`: Double base score

**Common Fields:**
- `modified_offer_score`: Double business-adjusted score
- `final_score`: Double final ranking score
- `offer_value`: Double customer-facing price
- `price`: Double (same as offer_value)
- `cost`: Double business cost
- `uuid`: String request UUID

**Optional Custom Fields:**
- `contextual_variable_one`: String
- `contextual_variable_two`: String
- `alpha`, `beta`: Double (MAB parameters)
- `weighting`: Double
- Any custom fields your business logic needs

---

## Common Use Cases and Patterns

### Use Case 1: Customer Segment-Based Recommendations

**Goal**: Show different offers based on customer segment.

**Pre-Score Plugin:**
```java
// Set contextual variable based on segment
JSONObject featuresObj = params.getJSONObject("featuresObj");
JSONObject in_params = params.getJSONObject("in_params");

if (featuresObj.has("customer_segment")) {
    in_params.put("contextual_variable_one", 
                  featuresObj.getString("customer_segment"));
}
```

**Post-Score Plugin:**
```java
// Boost scores for segment-relevant offers
String segment = featuresObj.getString("customer_segment");
if (offerKey.startsWith(segment + "_")) {
    adjustedScore = baseScore * 1.2; // 20% boost
}
```

### Use Case 2: Margin Optimization

**Goal**: Prioritize high-margin offers while maintaining relevance.

**Post-Score Plugin:**
```java
double margin = offerValue - offerCost;
double marginPercent = margin / offerValue;

// Balance relevance (baseScore) with margin
double adjustedScore = (baseScore * 0.7) + (marginPercent * 0.3);
```

### Use Case 3: Time-Based Eligibility

**Goal**: Only show offers available at current time.

**Post-Score Plugin:**
```java
JSONObject locations = getLocations(params);
if (locations != null && locations.has(offerKey)) {
    JSONObject location = locations.getJSONObject(offerKey);
    
    String day = params.getJSONObject("in_params").getString("day");
    String time = params.getJSONObject("in_params").getString("time");
    
    if (!isOpen(location, day, time)) {
        continue; // Skip closed offers
    }
}
```

### Use Case 4: Competitive Pricing

**Goal**: Adjust prices dynamically based on market conditions.

**Post-Score Plugin:**
```java
// Get market price (from external source or feature store)
double marketPrice = getMarketPrice(offerKey);
double basePrice = offerMatrix.getDouble("price");

// Apply competitive pricing
if (basePrice > marketPrice * 1.1) {
    // Too expensive - reduce score
    adjustedScore = baseScore * 0.8;
    // Optionally adjust price
    finalPrice = marketPrice * 1.05; // 5% above market
}
```

### Use Case 5: A/B Testing

**Goal**: Test different scoring algorithms.

**Post-Score Plugin:**
```java
String experimentGroup = featuresObj.optString("experiment_group", "control");

if (experimentGroup.equals("treatment")) {
    // New algorithm: higher weight on margin
    adjustedScore = (baseScore * 0.5) + (marginPercent * 0.5);
} else {
    // Control: original algorithm
    adjustedScore = baseScore * (1 + marginPercent * 0.2);
}
```

---

## Best Practices

### 1. Error Handling and Defensive Coding

**Always handle errors gracefully:**

```java
try {
    // Your logic
} catch (Exception e) {
    LOGGER.error("PluginName:E001:UUID: " + params.optString("uuid", "unknown") + 
                " Error: " + e.getMessage());
    // Return unmodified object (never null)
    return params; // or predictModelMojoResult
}
```

**Use defensive checks - never assume data exists:**

```java
// Always check before accessing
if (params.has("featuresObj") && !params.isNull("featuresObj")) {
    JSONObject featuresObj = params.getJSONObject("featuresObj");
    if (featuresObj != null && featuresObj.has("customer_segment")) {
        // Safe to access
        String segment = featuresObj.optString("customer_segment", "DEFAULT");
    }
}
```

**Use safe access methods with defaults:**

```java
// Preferred: optString/optInt/optDouble provide defaults
String segment = featuresObj.optString("customer_segment", "DEFAULT");
int count = params.optInt("resultcount", 1);
double score = option.optDouble("arm_reward", 0.0);
```

**See the "Code Safety and Preventing Breakage" section below for comprehensive defensive coding patterns.**

### 2. Performance

**Minimize database calls:**
- Use provided sessions efficiently
- Cache data in params if used multiple times
- Avoid nested loops with database queries

**Use efficient sorting:**
```java
// Use provided utility
JSONArray sorted = JSONArraySort.sortArray(finalOffers, 
                                           "modified_offer_score", 
                                           "double", "d");
```

**Log performance:**
```java
double startTime = System.nanoTime();
// ... your logic ...
double endTime = System.nanoTime();
LOGGER.info("PluginName:I001: execution time: " + 
           (endTime - startTime) / 1000000 + " ms");
```

### 3. Code Organization

**Extract complex logic:**
```java
private double calculateAdjustedScore(double baseScore, 
                                      double offerValue, 
                                      double offerCost,
                                      JSONObject featuresObj) {
    // Complex calculation logic here
    return adjustedScore;
}
```

**Use constants:**
```java
private static final double MARGIN_BOOST_FACTOR = 0.3;
private static final double VIP_SCORE_MULTIPLIER = 1.2;
```

**Document business rules:**
```java
/**
 * Applies business logic to adjust scores:
 * - 30% boost based on margin percentage
 * - 20% boost for VIP customers
 * - 10% boost for recent customers (< 30 days)
 */
private double applyBusinessLogic(...) {
    // ...
}
```

### 4. Testing Considerations

**Test edge cases:**
- Missing optional keys
- Empty arrays/objects
- Null values
- Zero scores
- Negative values
- Very large numbers

**Test with different configurations:**
- Different customer segments
- Different channels
- Different explore values
- Missing offer matrix entries

**Verify contract compliance:**
- Method signature matches exactly
- Return type is correct
- Required fields are present
- Data structures are preserved

---

## Code Safety and Preventing Breakage

### Why Code Safety Matters

When developing plugins, you're working with a **production runtime system** that processes real customer requests. A single unhandled exception or null pointer can:

- **Break the entire scoring pipeline** for that request
- **Cause cascading failures** if errors aren't caught
- **Return invalid results** to customers
- **Make debugging difficult** without proper error handling

The plugin system uses **dynamic loading via reflection**, which means:
- Errors at runtime can't be caught at compile time
- Method signature mismatches cause silent failures
- Missing data causes exceptions, not warnings

### The Defensive Coding Mindset

**Always assume data might be missing, null, or malformed.** The platform handles many edge cases, but your plugin must handle its own data access safely.

### Key Safety Principles

#### 1. Never Trust Input Data

**Problem:** You might assume `featuresObj` always has `customer_segment`, but:
- New customers might not have this field yet
- Data migration might have gaps
- API calls might omit optional fields

**Solution:** Always check before accessing:
```java
// ❌ Dangerous assumption
String segment = featuresObj.getString("customer_segment");

// ✅ Safe access
String segment = featuresObj.optString("customer_segment", "DEFAULT");
// or
if (featuresObj.has("customer_segment")) {
    segment = featuresObj.getString("customer_segment");
}
```

#### 2. Handle Null at Every Level

**Problem:** JSONObject can contain null values, and `getJSONObject()` can return null if the key doesn't exist.

**Solution:** Check at every level:
```java
// ❌ Will crash if in_params is missing
JSONObject inParams = params.getJSONObject("in_params");
String value = inParams.getString("key");

// ✅ Safe nested access
String value = null;
if (params.has("in_params") && !params.isNull("in_params")) {
    JSONObject inParams = params.getJSONObject("in_params");
    if (inParams != null && inParams.has("key")) {
        value = inParams.getString("key");
    }
}
```

#### 3. Validate Arrays Before Iteration

**Problem:** Helper methods might return null or empty arrays.

**Solution:** Always validate:
```java
// ❌ Will crash if options is null
JSONArray options = getOptions(params);
for (int i = 0; i < options.length(); i++) {
    // ...
}

// ✅ Safe iteration
JSONArray options = getOptions(params);
if (options != null && options.length() > 0) {
    for (int i = 0; i < options.length(); i++) {
        try {
            JSONObject option = options.getJSONObject(i);
            if (option != null) {
                // Process safely
            }
        } catch (Exception e) {
            LOGGER.warn("Skipping invalid option: " + e.getMessage());
            continue; // Don't break the loop
        }
    }
}
```

#### 4. Use Safe Type Conversions

**Problem:** JSON can store numbers as strings, or have type mismatches.

**Solution:** Use safe conversion utilities:
```java
// ❌ May throw ClassCastException
double score = (double) option.get("arm_reward");

// ✅ Safe conversion
double score = DataTypeConversions.getDouble(option, "arm_reward");
// or
double score = option.optDouble("arm_reward", 0.0);
```

#### 5. Wrap Everything in Try-Catch

**Problem:** Any external data access can fail unexpectedly.

**Solution:** Comprehensive error handling:
```java
public static JSONObject getPostPredict(...) {
    // Validate inputs first
    if (predictModelMojoResult == null) {
        LOGGER.error("PluginName:E001: predictModelMojoResult is null");
        return new JSONObject(); // Never return null
    }
    
    try {
        // All your logic here
        // ...
        
        return predictModelMojoResult;
        
    } catch (JSONException e) {
        LOGGER.error("PluginName:E002:UUID: " + 
                    params.optString("uuid", "unknown") + 
                    " JSON error: " + e.getMessage());
        // Return unmodified result, not null
        return predictModelMojoResult;
        
    } catch (Exception e) {
        LOGGER.error("PluginName:E999:UUID: " + 
                    params.optString("uuid", "unknown") + 
                    " Unexpected error: " + e.getMessage(), e);
        // Always return something valid
        return predictModelMojoResult;
    }
}
```

### Common Pitfalls and How to Avoid Them

#### Pitfall 1: Chained Method Calls

**Dangerous:**
```java
String segment = params.getJSONObject("in_params")
                      .getJSONObject("customer")
                      .getString("segment");
```

**Safe:**
```java
String segment = null;
if (params.has("in_params")) {
    JSONObject inParams = params.getJSONObject("in_params");
    if (inParams != null && inParams.has("customer")) {
        JSONObject customer = inParams.getJSONObject("customer");
        if (customer != null) {
            segment = customer.optString("segment", "DEFAULT");
        }
    }
}
```

#### Pitfall 2: Assuming Array Length

**Dangerous:**
```java
JSONArray finalOffers = predictModelMojoResult.getJSONArray("final_result");
JSONObject firstOffer = finalOffers.getJSONObject(0);
```

**Safe:**
```java
JSONArray finalOffers = predictModelMojoResult.optJSONArray("final_result");
if (finalOffers != null && finalOffers.length() > 0) {
    JSONObject firstOffer = finalOffers.getJSONObject(0);
    // Process safely
}
```

#### Pitfall 3: Not Handling Missing Optional Data

**Dangerous:**
```java
double margin = offerValue - offerCost;
double adjustedScore = baseScore * (1 + margin / offerValue);
```

**Safe:**
```java
double margin = 0.0;
double adjustedScore = baseScore; // Default

if (offerValue > 0 && offerCost >= 0) {
    margin = offerValue - offerCost;
    double marginPercent = margin / offerValue;
    adjustedScore = baseScore * (1 + marginPercent);
} else {
    LOGGER.warn("Invalid pricing, using base score");
}
```

### The Safe Modification Pattern

When adding new functionality, follow this pattern:

1. **Extract data safely** with validation
2. **Apply new logic** with try-catch
3. **Provide fallback** to original behavior
4. **Log errors** for debugging
5. **Return valid result** even on error

**Example:**
```java
// Adding new scoring logic
double adjustedScore = baseScore; // Default to original

try {
    // New logic here
    if (featuresObj.has("customer_segment")) {
        String segment = featuresObj.getString("customer_segment");
        if ("VIP".equals(segment)) {
            adjustedScore = baseScore * 1.2; // New boost
        }
    }
} catch (Exception e) {
    LOGGER.warn("New scoring logic failed, using base score: " + e.getMessage());
    adjustedScore = baseScore; // Fallback to original
}

// Use adjustedScore (guaranteed to have a value)
```

### Error Recovery Strategies

#### Strategy 1: Graceful Degradation

If new logic fails, fall back to original behavior:
```java
double score = calculateNewScore(baseScore, featuresObj);
if (score <= 0 || Double.isNaN(score)) {
    LOGGER.warn("New calculation failed, using base score");
    score = baseScore; // Fallback
}
```

#### Strategy 2: Skip Invalid Items

When processing arrays, skip problematic items rather than failing entirely:
```java
for (int i = 0; i < options.length(); i++) {
    try {
        JSONObject option = options.getJSONObject(i);
        // Process option
    } catch (Exception e) {
        LOGGER.warn("Skipping option " + i + ": " + e.getMessage());
        continue; // Skip this one, process others
    }
}
```

#### Strategy 3: Return Empty Results

If you can't process anything, return empty but valid structure:
```java
if (finalOffers.length() == 0) {
    LOGGER.warn("No valid offers generated");
    predictModelMojoResult.put("final_result", new JSONArray()); // Empty but valid
    return predictModelMojoResult;
}
```

### Testing Your Safety Measures

Before deploying, test these scenarios:

1. **Missing data:** Remove optional fields from test data
2. **Null values:** Set fields to null explicitly
3. **Empty arrays:** Pass empty JSONArrays
4. **Type mismatches:** Use wrong types (string where number expected)
5. **Malformed data:** Invalid JSON structures
6. **Edge values:** Zero, negative, very large numbers

### The Safety Checklist

Before making any changes, verify:

- [ ] All JSONObject access uses `has()` or `opt*()` methods
- [ ] All array access checks length/bounds
- [ ] All risky operations wrapped in try-catch
- [ ] All return paths return valid objects (never null)
- [ ] Required fields preserved in output
- [ ] Error cases return unmodified input (graceful degradation)
- [ ] New logic has fallback to original behavior
- [ ] Type conversions use safe methods
- [ ] Helper method returns validated before use
- [ ] Logging added for error cases with UUID
- [ ] Method signature unchanged
- [ ] Tested with missing/null/empty data

### Why This Matters

**Production Impact:**
- One unhandled exception can break a customer's request
- Missing error handling makes debugging nearly impossible
- Invalid returns can cause downstream failures

**Maintenance:**
- Safe code is easier to modify later
- Clear error messages speed up troubleshooting
- Graceful degradation prevents cascading failures

**Reliability:**
- Defensive coding handles edge cases automatically
- Fallback strategies ensure the system keeps working
- Proper logging helps identify issues quickly

---

## Summary

The ecosystem.Ai plugin system provides powerful extensibility while maintaining platform stability through strict contracts:

1. **Pre-Score Plugins** prepare data before model scoring
2. **Post-Score Plugins** process results and apply business logic
3. **Dynamic Data Loading** enables real-time configuration changes
4. **Score Customization** allows business rules to influence recommendations
5. **Contract Adherence** ensures plugins integrate seamlessly
6. **Code Safety** prevents runtime failures and ensures graceful degradation

Remember: You have complete freedom in your implementation logic, but you must follow the contracts exactly and code defensively. This design enables the platform to evolve while your customizations continue to work reliably in production.

**Key Takeaways:**
- Always validate data before accessing
- Never return null - always return valid objects
- Provide fallbacks for new logic
- Log errors with context (UUID) for debugging
- Test with missing/null/empty data

For detailed technical rules and patterns, see `.cursorrules` in the project root.
