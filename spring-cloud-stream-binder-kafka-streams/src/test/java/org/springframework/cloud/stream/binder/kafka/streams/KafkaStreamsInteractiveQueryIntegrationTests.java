/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.binder.kafka.streams;

import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Serialized;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.binder.kafka.streams.annotations.KafkaStreamsProcessor;
import org.springframework.cloud.stream.binder.kafka.streams.properties.KafkaStreamsBinderConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerde;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.rule.EmbeddedKafkaRule;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.messaging.handler.annotation.SendTo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.internal.verification.VerificationModeFactory.times;

/**
 * @author Soby Chacko
 * @author Gary Russell
 */
public class KafkaStreamsInteractiveQueryIntegrationTests {

	@ClassRule
	public static EmbeddedKafkaRule embeddedKafkaRule = new EmbeddedKafkaRule(1, true,
			"counts-id");

	private static EmbeddedKafkaBroker embeddedKafka = embeddedKafkaRule
			.getEmbeddedKafka();

	private static Consumer<String, String> consumer;

	@BeforeClass
	public static void setUp() throws Exception {
		Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("group-id",
				"false", embeddedKafka);
		consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(
				consumerProps);
		consumer = cf.createConsumer();
		embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "counts-id");
	}

	@AfterClass
	public static void tearDown() {
		consumer.close();
	}

	@Test
	public void testStateStoreRetrievalRetry() {

		KafkaStreams mock = Mockito.mock(KafkaStreams.class);
		KafkaStreamsBinderMetrics mockMetrics = Mockito.mock(KafkaStreamsBinderMetrics.class);
		KafkaStreamsRegistry kafkaStreamsRegistry = new KafkaStreamsRegistry(mockMetrics);
		kafkaStreamsRegistry.registerKafkaStreams(mock);
		KafkaStreamsBinderConfigurationProperties binderConfigurationProperties =
				new KafkaStreamsBinderConfigurationProperties(new KafkaProperties());
		binderConfigurationProperties.getStateStoreRetry().setMaxAttempts(3);
		InteractiveQueryService interactiveQueryService = new InteractiveQueryService(kafkaStreamsRegistry,
				binderConfigurationProperties);

		QueryableStoreType<ReadOnlyKeyValueStore<Object, Object>> storeType = QueryableStoreTypes.keyValueStore();
		try {
			interactiveQueryService.getQueryableStore("foo", storeType);
		}
		catch (Exception ignored) {

		}

		Mockito.verify(mock, times(3)).store("foo", storeType);
	}

	@Test
	public void testKstreamBinderWithPojoInputAndStringOuput() throws Exception {
		SpringApplication app = new SpringApplication(ProductCountApplication.class);
		app.setWebApplicationType(WebApplicationType.NONE);
		ConfigurableApplicationContext context = app.run("--server.port=0",
				"--spring.jmx.enabled=false",
				"--spring.cloud.stream.bindings.input.destination=foos",
				"--spring.cloud.stream.bindings.output.destination=counts-id",
				"--spring.cloud.stream.kafka.streams.binder.configuration.commit.interval.ms=1000",
				"--spring.cloud.stream.kafka.streams.binder.configuration.default.key.serde"
						+ "=org.apache.kafka.common.serialization.Serdes$StringSerde",
				"--spring.cloud.stream.kafka.streams.binder.configuration.default.value.serde"
						+ "=org.apache.kafka.common.serialization.Serdes$StringSerde",
				"--spring.cloud.stream.kafka.streams.bindings.input.consumer.applicationId=ProductCountApplication-abc",
				"--spring.cloud.stream.kafka.streams.binder.configuration.application.server"
						+ "=" + embeddedKafka.getBrokersAsString(),
				"--spring.cloud.stream.kafka.streams.binder.brokers="
						+ embeddedKafka.getBrokersAsString());
		try {
			receiveAndValidateFoo(context);
		}
		finally {
			context.close();
		}
	}

	private void receiveAndValidateFoo(ConfigurableApplicationContext context) {
		Map<String, Object> senderProps = KafkaTestUtils.producerProps(embeddedKafka);
		DefaultKafkaProducerFactory<Integer, String> pf = new DefaultKafkaProducerFactory<>(
				senderProps);
		KafkaTemplate<Integer, String> template = new KafkaTemplate<>(pf, true);
		template.setDefaultTopic("foos");
		template.sendDefault("{\"id\":\"123\"}");
		ConsumerRecord<String, String> cr = KafkaTestUtils.getSingleRecord(consumer,
				"counts-id");
		assertThat(cr.value().contains("Count for product with ID 123: 1")).isTrue();

		ProductCountApplication.Foo foo = context
				.getBean(ProductCountApplication.Foo.class);
		assertThat(foo.getProductStock(123).equals(1L));

		// perform assertions on HostInfo related methods in InteractiveQueryService
		InteractiveQueryService interactiveQueryService = context
				.getBean(InteractiveQueryService.class);
		HostInfo currentHostInfo = interactiveQueryService.getCurrentHostInfo();
		assertThat(currentHostInfo.host() + ":" + currentHostInfo.port())
				.isEqualTo(embeddedKafka.getBrokersAsString());

		HostInfo hostInfo = interactiveQueryService.getHostInfo("prod-id-count-store",
				123, new IntegerSerializer());
		assertThat(hostInfo.host() + ":" + hostInfo.port())
				.isEqualTo(embeddedKafka.getBrokersAsString());

		HostInfo hostInfoFoo = interactiveQueryService
				.getHostInfo("prod-id-count-store-foo", 123, new IntegerSerializer());
		assertThat(hostInfoFoo).isNull();
	}

	@EnableBinding(KafkaStreamsProcessor.class)
	@EnableAutoConfiguration
	public static class ProductCountApplication {

		@StreamListener("input")
		@SendTo("output")
		public KStream<?, String> process(KStream<Object, Product> input) {

			return input.filter((key, product) -> product.getId() == 123)
					.map((key, value) -> new KeyValue<>(value.id, value))
					.groupByKey(Serialized.with(new Serdes.IntegerSerde(),
							new JsonSerde<>(Product.class)))
					.count(Materialized.as("prod-id-count-store")).toStream()
					.map((key, value) -> new KeyValue<>(null,
							"Count for product with ID 123: " + value));
		}

		@Bean
		public Foo foo(InteractiveQueryService interactiveQueryService) {
			return new Foo(interactiveQueryService);
		}

		static class Foo {

			InteractiveQueryService interactiveQueryService;

			Foo(InteractiveQueryService interactiveQueryService) {
				this.interactiveQueryService = interactiveQueryService;
			}

			public Long getProductStock(Integer id) {
				ReadOnlyKeyValueStore<Object, Object> keyValueStore = interactiveQueryService
						.getQueryableStore("prod-id-count-store",
								QueryableStoreTypes.keyValueStore());

				return (Long) keyValueStore.get(id);
			}

		}

	}

	static class Product {

		Integer id;

		public Integer getId() {
			return id;
		}

		public void setId(Integer id) {
			this.id = id;
		}

	}

}
