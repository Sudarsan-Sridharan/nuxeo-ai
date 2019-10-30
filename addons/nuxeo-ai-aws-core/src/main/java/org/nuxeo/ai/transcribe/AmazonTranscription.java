/*
 * (C) Copyright 2006-2019 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 * Contributors:
 *     anechaev
 */
package org.nuxeo.ai.transcribe;

import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A POJO for marshaling response from Amazon Transcribe
 */
public class AmazonTranscription {

    protected String jobName;
    protected String accountId;
    protected Result results;
    protected String status;

    public List<String> getTranscripts() {
        return results.transcripts.stream()
                .map(t -> t.transcript)
                .collect(Collectors.toList());
    }

    public AmazonTranscription(@JsonProperty("jobName") String jobName,
                               @JsonProperty("accountId") String accountId,
                               @JsonProperty("results") Result results,
                               @JsonProperty("status") String status) {
        this.jobName = jobName;
        this.accountId = accountId;
        this.results = results;
        this.status = status;
    }

    public static class Result {

        protected List<Transcript> transcripts;
        protected List<Item> items;

        public Result(@JsonProperty("transcripts") List<Transcript> transcripts,
                      @JsonProperty("items") List<Item> items) {
            this.transcripts = transcripts;
            this.items = items;
        }
    }

    public static class Alternative {

        protected double confidence;
        protected String content;

        public Alternative(@JsonProperty("confidence") double confidence,
                           @JsonProperty("content") String content) {
            this.confidence = confidence;
            this.content = content;
        }
    }

    public static class Item {

        protected String startTime;
        protected String endTime;
        protected List<Alternative> alternatives;
        protected String type;

        public Item(@JsonProperty("start_time") String startTime,
                    @JsonProperty("end_time") String endTime,
                    @JsonProperty("alternatives") List<Alternative> alternatives,
                    @JsonProperty("type") String type) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.alternatives = alternatives;
            this.type = type;
        }
    }

    public static class Transcript {
        protected String transcript;

        public Transcript(@JsonProperty("transcript") String transcript) {
            this.transcript = transcript;
        }
    }
}

