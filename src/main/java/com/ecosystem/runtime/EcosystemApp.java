package com.ecosystem.runtime;

import com.ecosystem.runtime.continuous.RollingFeatures;
import com.ecosystem.utils.GlobalSettings;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springdoc.core.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.cassandra.CassandraDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.cassandra.CassandraReactiveDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoReactiveAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

@OpenAPIDefinition(
		servers = {
				@Server(url = "/", description = "Default Server URL")
		})
@SpringBootApplication(exclude = {
		KafkaAutoConfiguration.class,
		CassandraDataAutoConfiguration.class,
		CassandraReactiveDataAutoConfiguration.class,
		MongoDataAutoConfiguration.class,
		MongoRepositoriesAutoConfiguration.class,
		MongoAutoConfiguration.class,
		MongoReactiveAutoConfiguration.class
})
@Configuration
@EnableWebSecurity
@EnableScheduling
public class EcosystemApp extends WebSecurityConfigurerAdapter {
	public static String version;

	RollingMaster rollingMaster = new RollingMaster();
	private long count = 0;
	GlobalSettings settings;
	{
		try {
			settings = new GlobalSettings();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static void main(String[] args) {
        SpringApplication.run(EcosystemApp.class, args);
    }

	@Bean
	public GroupedOpenApi publicApi() {
		return GroupedOpenApi.builder()
				.group("ecosystem-public")
				.pathsToMatch("/**")
				.build();
	}

	@Bean
	public OpenAPI ecosystemAiApi() {
		return new OpenAPI()
				.info(
						new Info().title("ecosystem.Ai Client Pulse Responder API")
								.description("The ecosystem.Ai Client Pulse Responder Engine brings the power of real-time and near-time predictions to the enterprise. Implement your behavioral construct " +
										"and core hypotheses through a configurable prediction platform. If you don't know how the model is going to behave, use our behavioral tracker" +
										"to assist with selection and exploit the most successful options.")
								.version("v0.9.2")
								.license(new License().name("ecosystem.Ai 1.0").url("https://ecosystem.ai")))
				.externalDocs(new ExternalDocumentation()
						.description("Learn Ecosystem")
						.url("https://learn.ecosystem.ai")
				).components(new Components()
						.addSecuritySchemes("apiKeyScheme", new SecurityScheme()
								.type(SecurityScheme.Type.APIKEY)
								.in(SecurityScheme.In.HEADER)
								.name("X-API-KEY")
						)
				).addSecurityItem(new SecurityRequirement().addList("apiKeyScheme"));
	}

	/* This is to turn security off. Username and password is in the application.properties file */
	@Override
	protected void configure(HttpSecurity http) throws Exception {
        System.out.println("Loading...");
		http.csrf().disable()
				.authorizeRequests()
				.antMatchers(HttpMethod.GET, "/**").permitAll()
				.antMatchers(HttpMethod.POST, "/**").permitAll()
				.antMatchers(HttpMethod.PUT, "/**").permitAll();
		System.out.println("Loaded...");
    }

	/*****************************************************************************************************************
	 * Scheduling engine for real-time features, model creating and scoring updates.
	 *****************************************************************************************************************/
	@EnableScheduling
	@EnableAsync
	class ScheduledActivity {
		com.ecosystem.runtime.continuous.RollingMaster rollingMaster = new com.ecosystem.runtime.continuous.RollingMaster();

		private String uuid = null;
		private long count = 0;
		GlobalSettings settings;
		{
			try {
				settings = new GlobalSettings();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		/**
		 * PROCESS DYNAMIC CONFIGURATION: Continuous scheduling engine.
		 * Set MONITORING_DELAY in seconds for processing, default is set to 10 mins.
		 */
		@Async
		@Qualifier(value = "taskExecutor")
		// @Scheduled(cron = "*/20 * * * * *") // 240000 = 4 mins, 420000 = 7 mins
		@Scheduled(fixedDelayString = "${monitoring.delay}000", initialDelay = 10000)
		public void scheduleFixedRateTaskAsync() throws Exception {

			// TODO: Test if there are changes to ecosystem.properties and then call /refresh if it changed.

			/** PROCESS DYNAMIC CONFIGURATION: process current project_id only as defined in properties */
			settings = new GlobalSettings();
			String uuid = com.ecosystem.runtime.continuous.RollingMaster.checkCorpora(settings);

			if (uuid != null) {

				System.out.println("A==================================================================================================");
				System.out.println("A===>>> Execute Dynamic Engine (" + count + "): " + com.ecosystem.runtime.continuous.RollingMaster.nowDate());
				System.out.println("A==================================================================================================");

				// rollingNaiveBayes.process();

				/** PROCESS INDEXES ONCE PER STARTUP */
				if (count == 0)
					rollingMaster.indexes();

				rollingMaster.process();

			}

			count = count + 1;

		}
	}




	/*****************************************************************************************************************
	 * Scheduling engine for real-time features, model creating and scoring updates.
	 *****************************************************************************************************************/
	@EnableScheduling
	@EnableAsync
	class ScheduledActivityRealTimeTraining {
		com.ecosystem.runtime.continuous.RollingFeatures rollingFeatures = new RollingFeatures();
		private String uuid = null;
		private long count = 0;
		GlobalSettings settings;
		{
			try {
				settings = new GlobalSettings();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		/**
		 * Continous scheduling engine.
		 * Set FEATURE_DELAY in seconds for processing, default is set to 10 mins.
		 */
		@Async
		@Qualifier(value = "taskExecutor2")
		@Scheduled(fixedDelayString = "${feature.delay}000", initialDelay = 80000)
		public void scheduleFixedRateTaskAsync() throws Exception {

			System.out.println("F==================================================================================================");
			System.out.println("F===>>> Execute Features and Training Engine (" + count + "): " + com.ecosystem.runtime.continuous.RollingMaster.nowDate());
			System.out.println("F==================================================================================================");

			/** PROCESS REAL-TIME FEATURE CREATION */
			settings = new GlobalSettings();
			rollingFeatures.process();

			count = count + 1;

		}
	}

}
