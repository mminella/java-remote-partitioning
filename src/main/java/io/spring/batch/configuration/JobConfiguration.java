/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.spring.batch.configuration;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import io.spring.batch.domain.ColumnRangePartitioner;
import io.spring.batch.domain.Customer;
import io.spring.batch.domain.CustomerRowMapper;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.partition.PartitionHandler;
import org.springframework.batch.integration.partition.BeanFactoryStepLocator;
import org.springframework.batch.integration.partition.MessageChannelPartitionHandler;
import org.springframework.batch.integration.partition.StepExecutionRequestHandler;
import org.springframework.batch.item.database.BeanPropertyItemSqlParameterSourceProvider;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.integration.annotation.Aggregator;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.ExecutorChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.core.MessagingTemplate;
import org.springframework.integration.dsl.channel.MessageChannels;
import org.springframework.integration.redis.inbound.RedisQueueInboundGateway;
import org.springframework.integration.redis.outbound.RedisQueueOutboundGateway;
import org.springframework.integration.scheduling.PollerMetadata;
import org.springframework.messaging.PollableChannel;
import org.springframework.scheduling.support.PeriodicTrigger;

/**
 * @author Michael Minella
 */
@Configuration
public class JobConfiguration {

	@Autowired
	public JobBuilderFactory jobBuilderFactory;

	@Autowired
	public StepBuilderFactory stepBuilderFactory;

	@Autowired
	public DataSource dataSource;

	@Autowired
	public JobExplorer jobExplorer;

	@Bean
	public ColumnRangePartitioner partitioner() {
		ColumnRangePartitioner columnRangePartitioner = new ColumnRangePartitioner();

		columnRangePartitioner.setColumn("id");
		columnRangePartitioner.setDataSource(this.dataSource);
		columnRangePartitioner.setTable("customer");

		return columnRangePartitioner;
	}

	@Bean
	public RedisMessageListenerContainer container(RedisConnectionFactory connectionFactory) {
		RedisMessageListenerContainer container =
				new RedisMessageListenerContainer();

		container.setConnectionFactory(connectionFactory);
		container.afterPropertiesSet();

		return container;
	}

	@Bean
	public RedisQueueInboundGateway redisInbound(RedisConnectionFactory connectionFactory) {
		RedisQueueInboundGateway inbound = new RedisQueueInboundGateway("batch.requests", connectionFactory);

		inbound.setRequestChannel(inboundRequests());
		inbound.setReceiveTimeout(60000000l);
		inbound.setReplyChannel(outboundStaging());

		return inbound;
	}

	@Bean
	@ServiceActivator(inputChannel = "outboundRequests")
	public RedisQueueOutboundGateway redisOutbound(RedisConnectionFactory connectionFactory) {
		RedisQueueOutboundGateway gateway = new RedisQueueOutboundGateway("batch.requests", connectionFactory);

		gateway.setOutputChannel(inboundStaging());

		return gateway;
	}

	@Bean
	public ExecutorChannel outboundRequests() {
		return MessageChannels.executor("executorChannel", new SimpleAsyncTaskExecutor()).get();
	}

	@Bean
	public PollableChannel inboundStaging() {
		return new QueueChannel();
	}

	@Bean
	public DirectChannel outboundStaging() {
		return new DirectChannel();
	}

	@Bean
	public DirectChannel inboundRequests() {
		return new DirectChannel();
	}

	@Bean
	@ServiceActivator(inputChannel = "inboundRequests", outputChannel = "outboundStaging")
	public StepExecutionRequestHandler stepExecutionRequestHandler() {
		StepExecutionRequestHandler stepExecutionRequestHandler =
				new StepExecutionRequestHandler();

		stepExecutionRequestHandler.setStepLocator(new BeanFactoryStepLocator());
		stepExecutionRequestHandler.setJobExplorer(this.jobExplorer);

		return stepExecutionRequestHandler;
	}

	@Bean
	public MessagingTemplate messageTemplate() {
		MessagingTemplate messagingTemplate = new MessagingTemplate(outboundRequests());

		messagingTemplate.setReceiveTimeout(60000000l);

		return messagingTemplate;
	}

	@Bean(name = PollerMetadata.DEFAULT_POLLER)
	public PollerMetadata defaultPoller() {
		PollerMetadata pollerMetadata = new PollerMetadata();
		pollerMetadata.setTrigger(new PeriodicTrigger(10));
		return pollerMetadata;
	}

	@Bean
	@Aggregator(inputChannel = "inboundStaging", sendPartialResultsOnExpiry = "true", sendTimeout = "60000000")
	public PartitionHandler partitionHandler() throws Exception {
		MessageChannelPartitionHandler partitionHandler = new MessageChannelPartitionHandler();

		partitionHandler.setStepName("slaveStep");
		partitionHandler.setGridSize(4);
		partitionHandler.setMessagingOperations(messageTemplate());
		partitionHandler.setReplyChannel(inboundStaging());

		partitionHandler.afterPropertiesSet();

		return partitionHandler;
	}

	@Bean
	@StepScope
	public JdbcPagingItemReader<Customer> pagingItemReader(
			@Value("#{stepExecutionContext['minValue']}")Long minValue,
			@Value("#{stepExecutionContext['maxValue']}")Long maxValue) {
		System.out.println("reading " + minValue + " to " + maxValue);
		JdbcPagingItemReader<Customer> reader = new JdbcPagingItemReader<>();

		reader.setDataSource(this.dataSource);
		reader.setFetchSize(1000);
		reader.setRowMapper(new CustomerRowMapper());

		MySqlPagingQueryProvider queryProvider = new MySqlPagingQueryProvider();
		queryProvider.setSelectClause("id, firstName, lastName, birthdate");
		queryProvider.setFromClause("from customer");
		queryProvider.setWhereClause("where id >= " + minValue + " and id < " + maxValue);

		Map<String, Order> sortKeys = new HashMap<>(1);

		sortKeys.put("id", Order.ASCENDING);

		queryProvider.setSortKeys(sortKeys);

		reader.setQueryProvider(queryProvider);

		return reader;
	}

	@Bean
	@StepScope
	public JdbcBatchItemWriter<Customer> customerItemWriter() {
		JdbcBatchItemWriter<Customer> itemWriter = new JdbcBatchItemWriter<>();

		itemWriter.setDataSource(this.dataSource);
		itemWriter.setSql("INSERT INTO NEW_CUSTOMER VALUES (:id, :firstName, :lastName, :birthdate)");
		itemWriter.setItemSqlParameterSourceProvider(new BeanPropertyItemSqlParameterSourceProvider());
		itemWriter.afterPropertiesSet();

		return itemWriter;
	}

	@Bean
	public Step step1() throws Exception {
		return stepBuilderFactory.get("step1")
				.partitioner(slaveStep().getName(), partitioner())
				.step(slaveStep())
				.partitionHandler(partitionHandler())
				.build();
	}

	@Bean
	public Step slaveStep() {
		return stepBuilderFactory.get("slaveStep")
				.<Customer, Customer>chunk(1000)
				.reader(pagingItemReader(null, null))
				.writer(customerItemWriter())
				.build();
	}

	@Bean
	public Job job() throws Exception {
		return jobBuilderFactory.get("job")
				.start(step1())
				.build();
	}
}
