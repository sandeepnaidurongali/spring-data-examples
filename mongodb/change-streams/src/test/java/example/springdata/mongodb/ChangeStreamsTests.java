/*
 * Copyright 2018-2021 the original author or authors.
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
package example.springdata.mongodb;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.*;
import static org.springframework.data.mongodb.core.query.Criteria.*;
import static org.springframework.data.mongodb.core.query.Query.*;
import static org.springframework.data.mongodb.core.query.Update.*;

import java.time.Duration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.reactivestreams.client.MongoClients;
import example.springdata.mongodb.util.MongoContainers;
import org.assertj.core.api.Assertions;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.ChangeStreamOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.SimpleReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.core.messaging.ChangeStreamRequest;
import org.springframework.data.mongodb.core.messaging.DefaultMessageListenerContainer;
import org.springframework.data.mongodb.core.messaging.Message;
import org.springframework.data.mongodb.core.messaging.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

/**
 * A simple Test demonstrating required {@link Configuration} for consumption of MongoDB
 * <a href="https://docs.mongodb.com/manual/changeStreams/">Change Streams</a> using the sync and Reactive Streams Java
 * driver.
 *
 * @author Christoph Strobl
 * @author Mark Paluch
 */
@Testcontainers
@ExtendWith(SpringExtension.class)
@DataMongoTest
public class ChangeStreamsTests {

	@Container //
	private static MongoDBContainer mongoDBContainer = MongoContainers.getDefaultContainer();

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
	}

	@Autowired MessageListenerContainer container; // for imperative style

	@Autowired MongoTemplate template;

	@Autowired ReactiveMongoOperations reactiveTemplate; // for reactive style

	Person gabriel = new Person(new ObjectId(), "Gabriel", "Lorca", 30);
	Person michael = new Person(new ObjectId(), "Michael", "Burnham", 30);
	Person ash = new Person(new ObjectId(), "Ash", "Tyler", 35);

	/**
	 * Configuration? Yes we need a bit of it - Do not worry, it won't be much!
	 */
	@SpringBootApplication()
	static class Config {

		/**
		 * Configure {@link MongoClient} to enable
		 * {@link org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration}.
		 *
		 * @return the {@link MongoClient}.
		 */
		@Bean
		MongoClient mongoClient() {
			return com.mongodb.client.MongoClients.create(mongoDBContainer.getReplicaSetUrl());
		}

		/**
		 * Configure {@link SimpleMongoDbFactory} pointing to the embedded MongoDB connection.
		 *
		 * @return a new {@link SimpleReactiveMongoDatabaseFactory}.
		 */
		@Bean
		SimpleMongoClientDatabaseFactory mongoDbFactory() {
			return new SimpleMongoClientDatabaseFactory(mongoClient(), "changestreams");
		}

		/**
		 * Configure {@link SimpleReactiveMongoDatabaseFactory} pointing to the embedded MongoDB connection.
		 *
		 * @return a new {@link SimpleReactiveMongoDatabaseFactory}.
		 */
		@Bean
		SimpleReactiveMongoDatabaseFactory reactiveMongoDatabaseFactory() {
			return new SimpleReactiveMongoDatabaseFactory(MongoClients.create(mongoDBContainer.getReplicaSetUrl()),
					"changestreams");
		}

		/**
		 * Since listening to a <a href="https://docs.mongodb.com/manual/changeStreams/">Change Stream</a> using the sync
		 * MongoDB Java Driver is a blocking class, we need to move load to another {@link Thread} by simply using a
		 * {@link MessageListenerContainer}.
		 * <p/>
		 * As this is a {@link org.springframework.context.SmartLifecycle smart lifecycle component} we do actually not need
		 * to worry about its lifecycle, the resource allocation and freeing.
		 *
		 * @param template must not be {@literal null}.
		 * @return a new {@link MessageListenerContainer}
		 */
		@Bean
		MessageListenerContainer messageListenerContainer(MongoTemplate template) {

			return new DefaultMessageListenerContainer(template) {

				/* auto startup will be changed for M2, so this should no longer be required. */
				@Override
				public boolean isAutoStartup() {
					return true;
				}
			};
		}
	}

	/**
	 * Use the {@link MessageListenerContainer} registered within the
	 * {@link org.springframework.context.ApplicationContext} to subscribe to MongoDB Change Streams. Events published via
	 * {@link com.mongodb.client.ChangeStreamIterable} are passed to the
	 * {@link org.springframework.data.mongodb.core.messaging.MessageListener#onMessage(Message) MessageListener}.
	 */
	@Test
	public void imperativeChangeEvents() throws InterruptedException {

		var messageListener = new CollectingMessageListener<ChangeStreamDocument<Document>, Person>();

		var request = ChangeStreamRequest.builder(messageListener) //
				.collection("person") //
				.filter(newAggregation(match(where("operationType").is("insert")))) // we are only interested in inserts
				.build();

		var subscription = container.register(request, Person.class);
		subscription.await(Duration.ofMillis(200)); // wait till the subscription becomes active

		template.save(gabriel);
		template.save(ash);

		messageListener.awaitNextMessages(2);

		assertThat(messageListener.messageCount()).isEqualTo(2); // first two insert events, so far so good

		template.update(Person.class) //
				.matching(query(where("id").is(ash.id()))) //
				.apply(update("age", 40)) //
				.first();

		Thread.sleep(200);

		assertThat(messageListener.messageCount()).isEqualTo(2); // updates are skipped

		template.save(michael);

		messageListener.awaitNextMessages(1);

		assertThat(messageListener.messageCount()).isEqualTo(3); // there we go, all events received.
	}

	/**
	 * Use a {@link reactor.core.publisher.Flux} to subscribe to MongoDB Change Streams.
	 */
	@Test
	public void reactiveChangeEvents() {

		var changeStream = reactiveTemplate.changeStream("person",
				ChangeStreamOptions.builder().filter(newAggregation(match(where("operationType").is("insert")))).build(),
				Person.class);

		changeStream.as(StepVerifier::create) //
				.expectSubscription() //
				.expectNoEvent(Duration.ofMillis(200)) // wait till change streams becomes active

				// Save documents and await their change events
				.then(() -> {
					reactiveTemplate.save(gabriel).as(StepVerifier::create).expectNextCount(1).verifyComplete();
					reactiveTemplate.save(ash).as(StepVerifier::create).expectNextCount(1).verifyComplete();
				}).expectNextCount(2) //

				// Update a document
				.then(() -> {

					reactiveTemplate.update(Person.class) //
							.matching(query(where("id").is(ash.id()))) //
							.apply(update("age", 40)) //
							.first() //
							.as(StepVerifier::create) //
							.expectNextCount(1) //
							.verifyComplete();
				}).expectNoEvent(Duration.ofMillis(200)) // updates are skipped

				// Save another document and await its change event
				.then(() -> {
					reactiveTemplate.save(michael).as(StepVerifier::create).expectNextCount(1).verifyComplete();
				}).expectNextCount(1) // there we go, all events received.

				.thenCancel() // change streams are infinite streams, at some point we need to unsubscribe
				.verify();
	}
}
