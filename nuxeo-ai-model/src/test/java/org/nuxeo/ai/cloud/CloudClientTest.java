/*
 * (C) Copyright 2018 Nuxeo (http://nuxeo.com/) and others.
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
 * Contributors:
 *     Gethin James
 */
package org.nuxeo.ai.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.nuxeo.ai.model.AiDocumentTypeConstants.CORPUS_TYPE;
import static org.nuxeo.ai.model.serving.TestModelServing.createTestBlob;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ai.model.AiDocumentTypeConstants;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.platform.test.PlatformFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;
import com.github.tomakehurst.wiremock.junit.WireMockRule;

@RunWith(FeaturesRunner.class)
@Features(PlatformFeature.class)
@Deploy("org.nuxeo.ai.ai-model")
public class CloudClientTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(5089);

    @Inject
    protected CoreSession session;

    @Inject
    protected BlobManager manager;

    @Inject
    protected TransactionalFeature txFeature;

    @Inject
    protected CloudClient client;

    @Test
    public void testClient() {

        assertFalse(client.isAvailable());
        // Doesn't do anything because its not configured.
        client.uploadDataset(session.createDocumentModel("/", "not_used", CORPUS_TYPE));

        try {
            ((NuxeoCloudClient) client).configureClient(new CloudConfigDescriptor());
            fail();
        } catch (IllegalArgumentException e) {
            // Success
        }

    }

    @Test
    @Deploy("org.nuxeo.ai.ai-model:OSGI-INF/cloud-client-test.xml")
    public void testConfigured() throws IOException {
        ManagedBlob managedBlob = createTestBlob(manager);

        //Create a document
        DocumentModel doc = session.createDocumentModel("/", "corpora", CORPUS_TYPE);
        doc = session.createDocument(doc);
        String jobId = "testing1";
        String query = "SELECT * FROM Document WHERE ecm:primaryType = 'Note'";
        Long split = 77L;
        doc.setPropertyValue(AiDocumentTypeConstants.CORPUS_JOBID, jobId);
        doc.setPropertyValue(AiDocumentTypeConstants.CORPUS_SPLIT, split);
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", "dc:title");
        fields.put("type", "txt");
        doc.setPropertyValue(AiDocumentTypeConstants.CORPUS_INPUTS, (Serializable) Collections.singletonList(fields));
        fields.put("name", "dc:creator");
        doc.setPropertyValue(AiDocumentTypeConstants.CORPUS_OUTPUTS, (Serializable) Collections.singletonList(fields));
        doc.setPropertyValue(AiDocumentTypeConstants.CORPUS_QUERY, query);
        doc.setPropertyValue(AiDocumentTypeConstants.CORPUS_TRAINING_DATA, (Serializable) managedBlob);
        doc.setPropertyValue(AiDocumentTypeConstants.CORPUS_EVALUATION_DATA, (Serializable) managedBlob);
        Long documentsCountValue = 1000L;
        doc.setPropertyValue(AiDocumentTypeConstants.CORPUS_DOCUMENTS_COUNT, documentsCountValue);
        doc = session.createDocument(doc);
        txFeature.nextTransaction();
        client.uploadDataset(doc);
    }

    @Test
    public void testTitle() {
        NuxeoCloudClient nuxClient = (NuxeoCloudClient) client;
        assertEquals("2 features, 34 Training, 56 Evaluation, Export id xyz", nuxClient.makeTitle(34, 56, "xyz", 2));
        assertEquals("0 features, 100 Training, 206 Evaluation, Export id xyzx", nuxClient.makeTitle(100, 206, "xyzx", 0));
    }
}