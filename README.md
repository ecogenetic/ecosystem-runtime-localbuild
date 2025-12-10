# ecosystem.Ai Client Pulse Responder (Runtime)

A real-time and near-time prediction platform for enterprise behavioral analytics and recommendation systems. This Spring Boot application provides a configurable prediction engine that supports multiple machine learning algorithms, dynamic model selection, and customizable business logic through a plugin-based architecture.

## Overview

The ecosystem.Ai Runtime is a headless API-first platform that enables real-time scoring, recommendation generation, and behavioral tracking. It supports various ML algorithms including Multi-Armed Bandits (Thompson Sampling, Epsilon-Greedy), Naive Bayes, Q-Learning, and Neural Networks.

**Version:** 0.9.6.1.2  
**Build:** 2025-10.34  
**Java Version:** 17  
**Spring Boot Version:** 3.5.6

## Key Features

- **Real-time Scoring**: Perform predictions using pre-loaded MOJO models (H2O AutoML, XGBoost, GLM)
- **Dynamic Algorithm Selection**: Supports multiple algorithms (binaryThompson, epsilonGreedy, naiveBayes, behaviorAlgos, Network, QLearning)
- **Plugin Architecture**: Extensible pre-score and post-score logic through custom plugins
- **Multi-Armed Bandit (MAB)**: Built-in support for exploration/exploitation strategies
- **Feature Store Integration**: MongoDB-based feature store for customer data
- **Response Tracking**: Track recommendation acceptance and update models accordingly
- **Business Logic Engine**: Customizable business rules and calculations
- **RESTful API**: Comprehensive REST API with Swagger documentation
- **Scheduled Processing**: Continuous model updates and scoring via scheduled tasks

## Architecture

The application follows a plugin-based architecture with clear separation of concerns:

```text
src/main/java/com/ecosystem/
├── runtime/           # Core runtime application and scheduling
├── plugin/
│   ├── business/     # Business logic plugins (value calculation, offer groups)
│   ├── customer/     # Customer scoring plugins (pre-score, post-score)
│   └── reward/       # Reward calculation plugins for reinforcement learning
```

### Plugin Types

1. **Pre-Score Plugins** (`plugin.customer.*`): Transform input parameters before model scoring
   - `PrePredictCustomer`: Customer prediction preprocessing
   - `PreScoreDynamic`: Dynamic feature engineering
   - `PreScoreLookup`: Database parameter lookup

2. **Post-Score Plugins** (`plugin.customer.*`): Process model outputs and generate recommendations
   - `PostScoreRecommender`: Generic recommendation generation
   - `PostScoreBasic`: Basic post-processing
   - `PostScoreNetwork`: Network-based recommendations

3. **Business Logic Plugins** (`plugin.business.*`): Custom business rules
   - `BusinessLogic`: Main business logic router
   - `BusinessLogicValueCalc`: Value calculations
   - `BusinessLogicOfferGroups`: Offer grouping logic

4. **Reward Plugins** (`plugin.reward.*`): Reward calculation for reinforcement learning
   - `DefaultReward`: Default reward calculation
   - `QLearnRewardPlugin`: Q-Learning specific rewards

## Prerequisites

- **Java 17** or higher
- **Maven 3.6+**
- **MongoDB** (for feature store and logging)
- **Cassandra** (optional, for whitelist and configuration)
- Access to ecosystem.Ai Maven repository: `https://mavenecosystem.ai`

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
                    <url>https://mavenecosystem.ai</url>
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

The application exposes a RESTful API with Swagger documentation available at `/swagger-ui.html`.

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

#### 3. **Business Logic** - `POST /business`

Execute custom business logic calculations.

**Request:**

```json
{
  "business_logic": "value_calc",
  "business_logic_params": {...}
}
```

#### 4. **Predictor Response (Pre-loaded)** - `GET /predictorResponsePreLoad`

Perform prediction on pre-loaded model with database lookup.

**Query Parameters:**

- `valueJSON`: JSON string with prediction parameters
- `detail`: Response detail level (`none`, `basic`, or `all`)

**Example:**

```text
GET /predictorResponsePreLoad?valueJSON={"name":"predict1","mojo":"1","dbparam":true,"lookup":{"key":"customer","value":1234567890}}&detail=all
```

#### 5. **Offer Recommendations** - `GET /offerRecommendations`

Retrieve offer recommendations for a customer.

#### 6. **Update Offer Recommendations** - `PUT /offerRecommendations`

Update offer recommendations.

### API Authentication

The API supports API key authentication via the `X-API-KEY` header. Security can be configured in `RuntimeApplication.java`.

## Plugin Development

### Creating a Custom Pre-Score Plugin

1. Extend `PreScoreSuper` or implement the required interface
2. Implement `getPrePredict(JSONObject params, CqlSession session)`
3. Update `ecosystem.properties`:

   ```properties
   plugin.prescore=com.yourpackage.YourPreScoreClass
   ```

### Creating a Custom Post-Score Plugin

1. Extend the base post-score class or implement required interface
2. Implement post-processing logic
3. Update `ecosystem.properties`:

   ```properties
   plugin.postscore=com.yourpackage.YourPostScoreClass
   ```

### Creating a Custom Reward Plugin

1. Extend `RewardSuper`
2. Implement `reward(JSONObject params)` method
3. Return updated params with reward values

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

ecosystem.Ai 1.0 - See <https://ecosystem.Ai> for details

## Support

- **Documentation**: <https://developerecosystem.ai>
- **Website**: <https://ecosystem.Ai>
- **API Documentation**: Available at `/swagger-ui.html` when running

## Contributing

**Note**: Please do not alter the POM file without checking with an ecosystem.Ai specialist.

When contributing:

1. Follow the plugin architecture patterns
2. Keep business logic in plugin classes, not in controllers
3. Ensure all external calls are properly handled with resilience patterns
4. Update configuration documentation for new features

---

**Built with Spring Boot 3.5.6 | Java 17 | ecosystem.Ai Platform**
