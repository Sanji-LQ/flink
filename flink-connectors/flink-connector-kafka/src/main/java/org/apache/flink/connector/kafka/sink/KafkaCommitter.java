/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.kafka.sink;

import org.apache.flink.api.connector.sink.Committer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.InvalidTxnStateException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Committer implementation for {@link KafkaSink}
 *
 * <p>The committer is responsible to finalize the Kafka transactions by committing them.
 */
class KafkaCommitter implements Committer<KafkaCommittable> {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaCommitter.class);

    private final Properties kafkaProducerConfig;

    @Nullable private FlinkKafkaInternalProducer<?, ?> recoveryProducer;

    KafkaCommitter(Properties kafkaProducerConfig) {
        this.kafkaProducerConfig = kafkaProducerConfig;
    }

    @Override
    public List<KafkaCommittable> commit(List<KafkaCommittable> committables) throws IOException {
        List<KafkaCommittable> retryableCommittables = new ArrayList<>();
        for (KafkaCommittable committable : committables) {
            final String transactionalId = committable.getTransactionalId();
            LOG.debug("Committing Kafka transaction {}", transactionalId);
            Optional<Recyclable<? extends FlinkKafkaInternalProducer<?, ?>>> recyclable =
                    committable.getProducer();
            FlinkKafkaInternalProducer<?, ?> producer;
            try {
                producer =
                        recyclable
                                .<FlinkKafkaInternalProducer<?, ?>>map(Recyclable::getObject)
                                .orElseGet(() -> getRecoveryProducer(committable));
                producer.commitTransaction();
                recyclable.ifPresent(Recyclable::close);
            } catch (InvalidTxnStateException e) {
                LOG.warn(
                        "Unable to commit recovered transaction ({}) because it's in an invalid state. "
                                + "Most likely the transaction has been aborted for some reason. Please check the Kafka logs for more details.",
                        committable,
                        e);
                recyclable.ifPresent(Recyclable::close);
            } catch (ProducerFencedException e) {
                LOG.warn(
                        "Unable to commit recovered transaction ({}) because its producer is already fenced."
                                + " This means that you either have a different producer with the same '{}' (this is"
                                + " unlikely with the '{}' as all generated ids are unique and shouldn't be reused)"
                                + " or recovery took longer than '{}' ({}ms). In both cases this most likely signals data loss,"
                                + " please consult the Flink documentation for more details.",
                        committable,
                        ProducerConfig.TRANSACTIONAL_ID_CONFIG,
                        KafkaSink.class.getSimpleName(),
                        ProducerConfig.TRANSACTION_TIMEOUT_CONFIG,
                        FlinkKafkaProducer.getTransactionTimeout(kafkaProducerConfig),
                        e);
                recyclable.ifPresent(Recyclable::close);
            } catch (Throwable e) {
                LOG.warn("Cannot commit Kafka transaction, retrying.", e);
                retryableCommittables.add(committable);
            }
        }
        return retryableCommittables;
    }

    @Override
    public void close() throws Exception {
        if (recoveryProducer != null) {
            recoveryProducer.close();
        }
    }

    /**
     * Creates a producer that can commit into the same transaction as the upstream producer that
     * was serialized into {@link KafkaCommittable}.
     */
    private FlinkKafkaInternalProducer<?, ?> getRecoveryProducer(KafkaCommittable committable) {
        if (recoveryProducer == null) {
            recoveryProducer =
                    new FlinkKafkaInternalProducer<>(
                            kafkaProducerConfig, committable.getTransactionalId());
        } else {
            recoveryProducer.setTransactionId(committable.getTransactionalId());
        }
        recoveryProducer.resumeTransaction(committable.getProducerId(), committable.getEpoch());
        return recoveryProducer;
    }
}