/*
 * Copyright (c) 2020, 2022 Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.coherence.plugin.visualvm.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.oracle.coherence.plugin.visualvm.GlobalPreferences;
import com.oracle.coherence.plugin.visualvm.panel.CoherencePersistencePanel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.swing.JOptionPane;

import static com.oracle.coherence.plugin.visualvm.Localization.getLocalText;


/**
 * The {@link RequestSender} based on Http(RESTful).
 *
 * @author sr 11.10.2017
 * @since Coherence 12.2.1.4.0
 */
public class HttpRequestSender
        implements RequestSender
    {
    // ------ constructors --------------------------------------------------

    /**
     * Create an HttpRequestSender object.
     *
     * @param sUrl the URL of the management server
     */
    public HttpRequestSender(String sUrl)
        {
        if (sUrl == null)
            {
            throw new IllegalArgumentException("URL must not be null");
            }

        f_sUrl = sUrl;
        // Managed Coherence Servers URL http://<admin-host>:<admin-port>/management/coherence/<version>/clusters
        f_fisWebLogic = f_sUrl.contains("/management/coherence/") && f_sUrl.contains("clusters");
        f_isSSl       = f_sUrl.startsWith("https");

        if (f_isSSl)
            {
            try
                {
                initSSL();
                }
            catch (Exception e)
                {
                LOGGER.warning("Unable to initialize SSL: " + e);
                e.printStackTrace();
                }
            }
        }

    // ------ RequestSender interface ---------------------------------------

    @Override
    public List<Attribute> getAllAttributes(ObjectName objName)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath();
        JsonNode rootNode = getResponseJson(sendGetRequest(modifyTarget(objName, urlBuilder)));

        // in case of back cache or storage manager we have to get the first item
        if (objName.getKeyProperty("type").equals("Cache") ||
            objName.getKeyProperty("type").equals("StorageManager"))
            {
            ArrayNode itemsNode = (ArrayNode) rootNode.get("items");
            if (itemsNode != null)
                {
                rootNode = itemsNode.get(0);
                }
            }

        List<Attribute> attributes = new ArrayList<>();
        if (rootNode instanceof ObjectNode)
            {
            ObjectNode objectNode = (ObjectNode) rootNode;
            objectNode.fields().forEachRemaining(e ->
                {
                JsonNode value = e.getValue();
                String sValue = value.asText();

                // handle an array value
                if (value instanceof ArrayNode)
                    {
                    StringBuilder sb = new StringBuilder();
                    value.forEach(s -> sb.append(s).append('\n'));
                    sValue = sb.toString();
                    }

                attributes.add(new Attribute(e.getKey(), sValue));
               });
            }

        return attributes;
        }

    @Override
    public String getAttribute(ObjectName objectName, String attribute)
            throws Exception
        {
        String restName = getRestName(attribute);
        URLBuilder urlBuilder = getBasePath();

        modifyTarget(objectName, urlBuilder).addQueryParameter("fields", restName);

        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));

        // check for WebLogic Server where the values are in items node
        if (f_fisWebLogic)
            {
            rootNode = getRootNodeForWebLogicServer(rootNode);
            }

        // in case of back cache, we have to get the first item
        if (objectName.getKeyProperty("type").equals("Cache"))
            {
            ArrayNode itemsNode = (ArrayNode) rootNode.get("items");
            if (itemsNode != null)
                {
                rootNode = itemsNode.get(0);
                }
            }
        JsonNode nodeAttribute = rootNode.get(restName);

        return nodeAttribute == null ? null : rootNode.get(restName).asText();
        }

    @Override
    public AttributeList getAttributes(ObjectName objectName, String[] asAttribute)
            throws Exception
        {
        Map<String, String> attributeMap =
                Arrays.stream(asAttribute).collect(Collectors.toMap(Function.identity(), s -> getRestName(s)));
        String attributes = attributeMap.values().stream().collect(Collectors.joining(","));

        URLBuilder urlBuilder = getBasePath();

        modifyTarget(objectName, urlBuilder).addQueryParameter("fields", attributes);

        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));

        // check for WebLogic Server where the values are in items node
        if (f_fisWebLogic)
            {
            rootNode = getRootNodeForWebLogicServer(rootNode);
            }

        // in case of back cache, we have to get the first item
        if (objectName.getKeyProperty("type").equals("Cache"))
            {
            ArrayNode itemsNode = (ArrayNode) rootNode.get("items");
            if (itemsNode != null)
                {
                rootNode = itemsNode.get(0);
                }
            }

        AttributeList list = new AttributeList();
        for (String attribute : asAttribute)
            {
            JsonNode node = rootNode.get(attributeMap.get(attribute));
            if (node != null)
                {
                list.add(new Attribute(attribute, node.asText()));
                }
            }
        return list;
        }

    @Override
    public Set<ObjectName> getAllCacheMembers()
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath();
        urlBuilder.addPathSegment("caches").addPathSegment("members")
                .addQueryParameter("fields", "name,service,domainPartition");

        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));
        JsonNode nodeCacheItems = rootNode.get("items");
        Set<ObjectName> setObjectNames = new HashSet<>();

        if (nodeCacheItems != null && nodeCacheItems.isArray())
            {
            for (int i = 0; i < nodeCacheItems.size(); i++)
                {
                JsonNode cacheMember = nodeCacheItems.get(i);
                Hashtable<String, String> mapKeysProps = new Hashtable<>();

                mapKeysProps.put("name", cacheMember.get("name").asText());
                mapKeysProps.put("service", cacheMember.get("service").asText());

                JsonNode domainPartition = cacheMember.get("domainPartition");
                if (domainPartition != null)
                    {
                    mapKeysProps.put("domainPartition", domainPartition.asText());
                    }
                setObjectNames.add(new ObjectName("Coherence", mapKeysProps));
                }
            }

        return setObjectNames;
        }

    @Override
    public Set<ObjectName> getAllJournalMembers(String sJournalType)
            throws Exception
        {
        String sJournalUrlType = sJournalType.equals("FlashJournalRM")
                                 ? "flash"
                                 : "ram";

        URLBuilder urlBuilder = getBasePath();
        urlBuilder.addPathSegment("journal")
                .addPathSegment(sJournalUrlType).addPathSegment("members")
                .addQueryParameter("fields", "nodeId,type,name");

        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));
        JsonNode nodeJournalMemberItems = rootNode.get("items");
        Set<ObjectName> setObjectNames = new HashSet<>();

        if (nodeJournalMemberItems != null && nodeJournalMemberItems.isArray())
            {
            for (int i = 0; i < nodeJournalMemberItems.size(); i++)
                {
                JsonNode nodeJournalMember = nodeJournalMemberItems.get(i);
                Hashtable<String, String> mapKeysProps = new Hashtable<>();

                mapKeysProps.put("name", nodeJournalMember.get("name").asText());
                mapKeysProps.put("type", nodeJournalMember.get("type").asText());
                mapKeysProps.put("nodeId", nodeJournalMember.get("nodeId").asText());

                setObjectNames.add(new ObjectName("Coherence", mapKeysProps));
                }
            }

        return setObjectNames;
        }

    @Override
    public Set<ObjectName> getCacheMembers(String sServiceName, String sCacheName, String sDomainPartition)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sServiceName)).addPathSegment("caches")
                .addPathSegment(sCacheName).addPathSegment("members");
        if (sDomainPartition != null)
            {
            urlBuilder.addQueryParameter("domainPartition", sDomainPartition);
            }

        urlBuilder.addQueryParameter("fields", "service,name,type,tier,nodeId")
                .addQueryParameter("links", "");

        return getSetObjectNamesFromResponse(sendGetRequest(urlBuilder));
        }

    @Override
    public Set<ObjectName> getCacheStorageMembers(String sServiceName, String sCacheName, String sDomainPartition)
            throws Exception
        {
        // from the perspective of REST, storage and cache MBeans are merged
        return getCacheMembers(sServiceName, sCacheName, sDomainPartition);
        }

    @Override
    public Set<ObjectName> getAllClusters()
            throws Exception
        {
        if (!f_fisWebLogic)
            {
            // return immediately if we are not in WebLogic as only once cluster is possible
            // for non-WebLogic clusters
            return Collections.singleton(new ObjectName("Coherence:type=Cluster"));
            }

        // continue on and check for WebLogic Server
        String sClusterName = getClusterName();
        if (sClusterName != null)
            {
            // cluster name is set so return what we have chosen
            return Collections.singleton(new ObjectName(CLUSTER_PREFIX + sClusterName));
            }

        // build the list of clusters
        URLBuilder urlBuilder = getBasePath().addQueryParameter("links", "");
        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));
        JsonNode clusterItems = rootNode.get("items");
        Set<ObjectName> setObjectNames = new HashSet<>();

        if (clusterItems != null && clusterItems.isArray())
            {
            for (int k = 0; k < (clusterItems).size(); k++)
                {
                JsonNode cluster = clusterItems.get(k);

                setObjectNames.add(new ObjectName(CLUSTER_PREFIX + cluster.get("cluster").asText()));
                }
            }

        return setObjectNames;
        }

    @Override
    public Set<ObjectName> getHotCacheMembers()
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("hotcache")
                .addPathSegment("members");
        urlBuilder.addQueryParameter("fields", "name,type,nodeId");

        return getSetObjectNamesFromResponse(sendGetRequest(urlBuilder));
        }

    @Override
    public Set<ObjectName> getHotCachePerCacheAdapters(String sMember)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("hotcache")
                .addPathSegment("members").addPathSegment(sMember);

        urlBuilder.addQueryParameter("fields", "name,type,nodeId")
                .addQueryParameter("links", "");

        return getSetObjectNamesFromResponse(sendGetRequest(urlBuilder));
        }

    @Override
    public Set<ObjectName> getAllCoherenceWebMembers(String sSessionManager)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment("proxy").addPathSegment("members").addQueryParameter("fields", "name,type,nodeId");

        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));
        JsonNode nodeWebAppItems = (JsonNode) rootNode.get("items");
        Set<ObjectName> setObjectNames = new HashSet<>();

        if (nodeWebAppItems != null && nodeWebAppItems.isArray())
            {
            for (int k = 0; k < ((ArrayNode) nodeWebAppItems).size(); k++)
                {
                JsonNode webAppMember = (JsonNode) nodeWebAppItems.get(k);

                if (webAppMember.get("type").asText().equals(sSessionManager))
                    {
                    Hashtable<String, String> mapKeysProps = new Hashtable<>();
                    webAppMember.fields().forEachRemaining(e -> mapKeysProps.put(e.getKey(), e.getValue().asText()));
                    setObjectNames.add(new ObjectName("Coherence", mapKeysProps));
                    }
                }
            }
        return setObjectNames;
        }

    @Override
    public Set<ObjectName> getCoherenceWebMembersForApplication(String sSessionManager, String sAppId)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("webApplications")
                .addPathSegment(sAppId).addPathSegment("members");

        urlBuilder.addQueryParameter("fields", "name,type,nodeId")
                .addQueryParameter("links", "");

        return getSetObjectNamesFromResponse(sendGetRequest(urlBuilder));
        }

    @Override
    public Set<ObjectName> getClusterMemberOS(int nodeId)
            throws Exception
        {
        return Collections.singleton(new ObjectName(
                "Coherence:type=Platform,Domain=java.lang,subType=OperatingSystem,nodeId="
                + nodeId + ",*"));
        }

    @Override
    public Set<ObjectName> getAllClusterMembers()
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("members");
        urlBuilder.addQueryParameter("fields", "type,nodeId")
                .addQueryParameter("links", "");

        return getSetObjectNamesFromResponse(sendGetRequest(urlBuilder));
        }

    @Override
    public Set<ObjectName> getAllExecutorMembers() throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("executors").addPathSegment("members");
        urlBuilder.addQueryParameter("links", "").addQueryParameter("fields", "name");

        return getSetObjectNamesFromResponse(sendGetRequest(urlBuilder));
        }


    @Override
    public Set<ObjectName> getAllGrpcProxyMembers()
            throws Exception
        {
        // not currently supported in REST
        return Collections.emptySet();
        }

    @Override
    public Set<ObjectName> getAllServiceMembers()
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment("members").addQueryParameter("fields", "name,type,domainPartition,nodeId," +
                                                                       "storageEnabled,persistenceActiveSpaceUsed,persistenceLatencyMax,persistenceLatencyAverage")
                .addQueryParameter("links", "");

        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));
        JsonNode nodeServiceMembersItems = (JsonNode) rootNode.get("items");
        Set<ObjectName> setObjectNames = new HashSet<>();

        if (nodeServiceMembersItems != null && nodeServiceMembersItems.isArray())
            {
            for (int i = 0; i < nodeServiceMembersItems.size(); i++)
                {
                JsonNode serviceMember = nodeServiceMembersItems.get(i);
                Hashtable<String, String> mapKeysProps = new Hashtable<>();

                serviceMember.fields().forEachRemaining(e -> mapKeysProps.put(e.getKey(), e.getValue().asText()));

                // the type attribute returned in the response is the service type, but in the object name,
                // type is always Service
                mapKeysProps.put("type", "Service");
                setObjectNames.add(new ObjectName("Coherence", mapKeysProps));
                }
            }

        return setObjectNames;
        }

    @Override
    public Set<ObjectName> getAllProxyServerMembers()
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment("proxy").addPathSegment("members")
                .addQueryParameter("fields", "name,type,domainPartition,nodeId");

        return getSetObjectNamesFromResponse(sendGetRequest(urlBuilder));
        }

    @Override
    public Set<ObjectName> getCompleteObjectName(ObjectName objectName)
            throws Exception
        {
        return Collections.singleton(objectName);
        }

    @Override
    public Set<ObjectName> getPartitionAssignmentObjectName(String sService, String sDomainPartition)
            throws Exception
        {
        return Collections.singleton(new ObjectName("Coherence:type=Service,name=" + sService +
                                                    (sDomainPartition != null
                                                     ? ",domainPartition=" + sDomainPartition
                                                     : "")));
        }

    @Override
    public String getScheduledDistributions(String sService, String sDomainPartition)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sService)).addPathSegment("partition").addPathSegment("scheduledDistributions");
        if (sDomainPartition != null)
            {
            urlBuilder.addQueryParameter("domainPartition", sDomainPartition);
            }

        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));
        return rootNode.get("scheduledDistributions").asText();
        }

    @Override
    public Set<Object[]> getPartitionAssignmentAttributes(String sService, String sDomainPartition)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sService)).addPathSegment("partition")
                .addQueryParameter("fields", "averagePartitionSizeKB,maxPartitionSizeKB,averageStorageSizeKB," +
                                             "maxStorageSizeKB,maxLoadNodeId");
        if (sDomainPartition != null)
            {
            urlBuilder.addQueryParameter("domainPartition", sDomainPartition);
            }

        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));

        Object[] oArr = new Object[5];
        oArr[0] = rootNode.get("averagePartitionSizeKB").asText();
        oArr[1] = rootNode.get("maxPartitionSizeKB").asText();
        oArr[2] = rootNode.get("averageStorageSizeKB").asText();
        oArr[3] = rootNode.get("maxStorageSizeKB").asText();
        oArr[4] = rootNode.get("maxLoadNodeId").asText();

        return Collections.singleton(oArr);
        }

    @Override
    public void invokeFederationOperation(String sService, String sOperation, String sParticipant)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sService)).addPathSegment("federation").addPathSegment("participants")
                .addPathSegment(sParticipant).addPathSegment(sOperation);
        sendPostRequest(urlBuilder);
        }

    @Override
    public Integer retrievePendingIncomingMessages(String sService)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sService)).addPathSegment("federation").addPathSegment("pendingIncomingMessages");

        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));
        return Integer.parseInt(rootNode.get("pendingIncomingMessages").asText());
        }

    @Override
    public Integer retrievePendingOutgoingMessages(String sService)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sService)).addPathSegment("federation").addPathSegment("pendingOutgoingMessages");

        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));
        return Integer.parseInt(rootNode.get("pendingOutgoingMessages").asText());
        }

    @Override
    public String getNodeState(Integer nNodeId)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("members")
                .addPathSegment(nNodeId + "").addPathSegment("state");

        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));
        return rootNode.get("state").asText();
        }

    /**
     * Issue a dump cluster heap request.
     *
     * @param sRole the role to dump for or null for all roles
     * @throws Exception if any errors
     */
    public void dumpClusterHeap(String sRole) throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("dumpClusterHeap");

        JsonNode rootNode = getResponseJson(sendPostRequest(urlBuilder, sRole));
        LOGGER.info(rootNode.toString());
        }

    @Override
    public String[] getSnapshots(String sService, String sDomainPartition)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sService)).addPathSegment("persistence").addPathSegment("snapshots");
        if (sDomainPartition != null)
            {
            urlBuilder.addQueryParameter("domainPartition", sDomainPartition);
            }

        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));
        JsonNode nodeSnapshots = rootNode.get("snapshots");

        List<String> listSnapshots = new ArrayList<>();
        if (nodeSnapshots != null && nodeSnapshots.isArray())
            {
            for (int i = 0; i < nodeSnapshots.size(); i++)
                {
                listSnapshots.add(nodeSnapshots.get(i).asText());
                }
            }

        return listSnapshots.toArray(new String[listSnapshots.size()]);
        }

    @Override
    public String[] getArchivedSnapshots(String sService, String sDomainPartition)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sService)).addPathSegment("persistence").addPathSegment("archives");
        if (sDomainPartition != null)
            {
            urlBuilder.addQueryParameter("domainPartition", sDomainPartition);
            }
        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));
        JsonNode nodeSnapshots = rootNode.get("archives");

        List<String> listSnapshots = new ArrayList<>();
        if (nodeSnapshots != null && nodeSnapshots.isArray())
            {
            for (int i = 0; i < nodeSnapshots.size(); i++)
                {
                listSnapshots.add(nodeSnapshots.get(i).asText());
                }
            }

        return listSnapshots.toArray(new String[listSnapshots.size()]);
        }

    @Override
    public void executePersistenceOperation(String sService,
                                            String sDomainPartition,
                                            String sOperationName,
                                            String sSnapshotName)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sService)).addPathSegment("persistence");

        switch (sOperationName)
            {
            case CoherencePersistencePanel.RETRIEVE_ARCHIVED_SNAPSHOT:
                urlBuilder.addPathSegment("archives").addPathSegment(sSnapshotName).addPathSegment("retrieve");
                sendPostRequest(urlBuilder);
                break;
            case CoherencePersistencePanel.REMOVE_ARCHIVED_SNAPSHOT:
                urlBuilder.addPathSegment("archives").addPathSegment(sSnapshotName);
                sendDeleteRequest(urlBuilder);
                break;
            case CoherencePersistencePanel.ARCHIVE_SNAPSHOT:
                urlBuilder.addPathSegment("archives").addPathSegment(sSnapshotName);
                sendPostRequest(urlBuilder);
                break;
            case CoherencePersistencePanel.CREATE_SNAPSHOT:
                urlBuilder.addPathSegment("snapshots").addPathSegment(sSnapshotName);
                sendPostRequest(urlBuilder);
                break;
            case CoherencePersistencePanel.REMOVE_SNAPSHOT:
                urlBuilder.addPathSegment("snapshots").addPathSegment(sSnapshotName);
                sendDeleteRequest(urlBuilder);
                break;
            case CoherencePersistencePanel.RECOVER_SNAPSHOT:
                urlBuilder.addPathSegment("snapshots").addPathSegment(sSnapshotName).addPathSegment("recover");
                sendPostRequest(urlBuilder);
                break;
            case CoherencePersistencePanel.FORCE_RECOVERY:
                sendPostRequest(urlBuilder);
                break;

            }
        }

    /**
     * Get the members of a service.
     *
     * @param sServiceName     the name of the service
     * @param sDomainPartition the domain partition to which the service
     *                         belongs
     * @return the members of a service
     * @throws Exception in case of errors
     */
    @Override
    public Set<ObjectName> getMembersOfService(String sServiceName, String sDomainPartition)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sServiceName)).addPathSegment("members");
        if (sDomainPartition != null)
            {
            urlBuilder.addQueryParameter("domainPartition", sDomainPartition);
            }

        urlBuilder.addQueryParameter("fields", "name,type,nodeId,domainPartition")
                .addQueryParameter("links", "");

        JsonNode rootNode = getResponseJson(sendGetRequest(urlBuilder));
        JsonNode nodeServiceMembers = (JsonNode) rootNode.get("items");
        Set<ObjectName> setObjectNames = new HashSet<>();

        if (nodeServiceMembers != null && nodeServiceMembers.isArray())
            {
            for (int i = 0; i < nodeServiceMembers.size(); i++)
                {
                JsonNode serviceMember = nodeServiceMembers.get(i);

                Hashtable<String, String> mapKeysProps = new Hashtable<>();
                serviceMember.fields().forEachRemaining(e -> mapKeysProps.put(e.getKey(), e.getValue().asText()));
                mapKeysProps.put("type", "Service");
                setObjectNames.add(new ObjectName("Coherence", mapKeysProps));
                }
            }
        return setObjectNames;
        }

    // ------ HttpRequestSender methods -------------------------------------

    /**
     * Get the cache data for the given service
     *
     * @param sServiceName     the service to which the cache belongs to
     * @param sDomainPartition the domain partition to which the service
     *                         belongs
     * @return the storage manager data for all the cache members of the cache
     * @throws Exception in case of errors
     */
    public JsonNode getListOfServiceCaches(String sServiceName, String sDomainPartition)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sServiceName)).addPathSegment("caches")
                .addQueryParameter("links", "")
                .addQueryParameter("fields", "nodeId,name,unitFactor,size,unitsBytes,units,memoryUnits,"
                                             + "averageMissMillis");

        if (sDomainPartition != null)
            {
            urlBuilder.addQueryParameter("domainPartition", sDomainPartition);
            }

        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the cache data for all services. Note this method uses the
     * enhancement 32530689 which also returns the service name.  This
     * enhancement will not work with WebLogic MT which is deprecated.
     *
     * @return the storage manager data for all the cache members of the cache
     * @throws Exception in case of errors
     */
    public JsonNode getListOfCaches()
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("caches")
                .addQueryParameter("links", "")
                .addQueryParameter("fields", "nodeId,name,unitFactor,size,unitsBytes,units,memoryUnits,"
                                             + "averageMissMillis,service");

        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Return the node storage in a single REST call.
     * @return the node storage data
     *
     * @throws Exception in case of errors
     */
    public JsonNode getNodeStorage()
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment("members")
                .addQueryParameter("links", "")
                .addQueryParameter("fields", "nodeId,ownedPartitionsPrimary");
        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the storage data for the given service
     *
     * @param sServiceName     the service to which the cache belongs to
     * @param sDomainPartition the domain partition to which the service
     *                         belongs
     * @return the storage manager data for all the cache members of the cache
     * @throws Exception in case of errors
     */
    public JsonNode getListOfStorageMembers(String sServiceName, String sDomainPartition)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sServiceName)).addPathSegment("members")
                .addQueryParameter("links", "")
                .addQueryParameter("fields", "ownedPartitionsPrimary,nodeId");

        if (sDomainPartition != null)
            {
            urlBuilder.addQueryParameter("domainPartition", sDomainPartition);
            }

        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get a list of all storage members.
     *
     * @return a list of all storage members
     * @throws Exception in case of errors
     */
    public JsonNode getAllStorageMembers() throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment("members")
                .addQueryParameter("links", "")
                .addQueryParameter("fields", "type,name,domainPartition,nodeId,persistenceMode," +
                                             "storageEnabled,persistenceActiveSpaceUsed,persistenceLatencyMax,persistenceLatencyAverage");
        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the data for all the StorageManager members of the provided cache.
     *
     * @param sServiceName     the service to which the cache belongs to
     * @param sCacheName       the name of the cache
     * @param sDomainPartition the domain partition to which the service
     *                         belongs
     * @return the storage manager data for all the cache members of the cache
     * @throws Exception in case of errors
     */
    public JsonNode getDataForStorageManagerMembers(String sServiceName, String sDomainPartition, String sCacheName)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sServiceName)).addPathSegment("caches").addPathSegment(sCacheName)
                .addPathSegment("members").addQueryParameter("fields",
                                                                   "nodeId,locksGranted,locksPending,listenerRegistrations,maxQueryDurationMillis,maxQueryDescription," +
                                                                   "nonOptimizedQueryAverageMillis,optimizedQueryAverageMillis,indexTotalUnits,indexingTotalMillis")
                .addQueryParameter("links", "");

        if (sDomainPartition != null)
            {
            urlBuilder.addQueryParameter("domainPartition", sDomainPartition);
            }

        return getResponseJson(sendGetRequest(urlBuilder));
        }


    /**
     * Get the data for cache members of a cache.
     *
     * @param sServiceName     the service to which the cache belongs to
     * @param sCacheName       the name of the cache
     * @param sDomainPartition the domain partition to which the service
     *                         belongs
     * @return the cache members data
     * @throws Exception in case of errors
     */
    public JsonNode getDataForCacheMembers(String sServiceName, String sCacheName, String sDomainPartition)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sServiceName)).addPathSegment("caches").addPathSegment(sCacheName)
                .addPathSegment("members");
        if (sDomainPartition != null)
            {
            urlBuilder.addQueryParameter("domainPartition", sDomainPartition);
            }

        urlBuilder.addQueryParameter("fields", "name,type,size,service,nodeId," +
                                               "domainPartition,tier,units,unitFactor,totalGets,totalPuts,cacheHits,cacheMisses,hitProbability")
                .addQueryParameter("links", "");

        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the data for all the cluster members,
     *
     * @return the data for all the cluster members
     * @throws Exception in case of errors
     */
    public JsonNode getListOfClusterMembers() throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("members");

        urlBuilder = urlBuilder.addQueryParameter("fields", "nodeId," +
                                                            "publisherSuccessRate,receiverSuccessRate," +
                                                            "sendQueueSize,memoryMaxMB,memoryAvailableMB,unicastAddress,roleName,unicastPort," +
                                                            "machineName,rackName,siteName,productEdition")
                .addQueryParameter("links", "");

        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the data for all services.
     *
     * @return the data for all services
     * @throws Exception in case of errors
     */
    public JsonNode getListOfServices() throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment("members")
                .addQueryParameter("fields", "name,type,domainPartition,nodeId," +
                                             "statusHA,memberCount,partitionsAll,partitionsEndangered," +
                                             "partitionsVulnerable,partitionsUnbalanced,requestPendingCount," +
                                             "storageEnabledCount,type")
                .addQueryParameter("links", "");
        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the data for all executors.
     *
     * @return the data for all executors
     * @throws Exception in case of errors
     */
    public JsonNode getExecutors() throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("executors").addPathSegment("members")
                     .addQueryParameter("links", "");
        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the data for all the proxy members in the cluster.
     *
     * @return the data for all the cluster members
     * @throws Exception in case of errors
     */
    public JsonNode getDataForProxyMembers() throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment("proxy").addPathSegment("members").addQueryParameter("fields", "hostIP,name,nodeId," +
                                                                                               "connectionCount,outgoingMessageBacklog,totalBytesReceived,totalBytesSent," +
                                                                                               "totalMessagesReceived,totalMessagesSent,protocol," +
                                                                                               "domainPartition,httpServerType,totalRequestCount," +
                                                                                               "totalErrorCount,requestsPerSecond,averageRequestTime," +
                                                                                               "responseCount1xx,responseCount2xx,responseCount3xx,responseCount4xx,responseCount5xx");

        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the data for all the service members in the cluster.
     *
     * @return the data for all service members
     * @throws Exception in case of errors
     */
    public JsonNode getDataForServiceMembers() throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment("members").addQueryParameter("fields", "name,type,domainPartition,nodeId,taskBacklog," +
                                                                       "threadCount,threadIdleCount,requestAverageDuration,taskAverageDuration");

        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the incoming data(OriginMBean) data for a participant.
     *
     * @param sServiceName     the name of the service
     * @param sParticipantName the name of the participant
     * @return the incoming data(OriginMBean) of the participant
     * @throws Exception in case of errors
     */
    public JsonNode getIncomingDataForParticipant(String sServiceName, String sParticipantName)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sServiceName)).addPathSegment("federation").addPathSegment("statistics")
                .addPathSegment("incoming").addPathSegment("participants")
                .addPathSegment(sParticipantName).addPathSegment("members");

        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the outgoing data(DestinationMBean) data for a participant.
     *
     * @param sServiceName     the name of the service
     * @param sParticipantName the name of the participant
     * @return the outgoing data(DestinationMBean) of the participant
     * @throws Exception in case of errors
     */
    public JsonNode getOutgoingDataForParticipant(String sServiceName, String sParticipantName)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sServiceName)).addPathSegment("federation").addPathSegment("statistics")
                .addPathSegment("outgoing").addPathSegment("participants").addPathSegment(sParticipantName)
                .addPathSegment("members");

        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the data for all the elastic data members of a particular type.
     *
     * @param sElasticDataType the data type of elastic members(ram/flash)
     * @return the data for all the elastic data members in the cluster
     * @throws Exception in case of errors
     */
    public JsonNode getDataForElasticDataMembers(String sElasticDataType)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("journal")
                .addPathSegment(sElasticDataType).addPathSegment("members")
                .addQueryParameter("fields", "nodeId,fileCount,maxJournalFilesNumber,maxFileSize,totalDataSize," +
                                             "compactionCount,exhaustiveCompactionCount,currentCollectorLoadFactor");

        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the aggregated data for all the proxy servers of a service.
     *
     * @param sServiceName     the name of the service
     * @param sDomainPartition the domain partition to which the service
     *                         belongs
     * @return the aggregated data across all proxy members of a service
     * @throws Exception in case of errors
     */
    public JsonNode getAggregatedProxyData(String sServiceName, String sDomainPartition)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sServiceName)).addPathSegment("proxy");
        if (sDomainPartition != null)
            {
            urlBuilder.addQueryParameter("domainPartition", sDomainPartition);
            }

        urlBuilder = urlBuilder.addQueryParameter("fields", "name,type,httpServerType,totalRequestCount," +
                                                            "totalErrorCount,requestsPerSecond,averageRequestTime,protocol")
                .addQueryParameter("links", "");

        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the aggregated data for a service.
     *
     * @param sServiceName     the name of the service
     * @param sDomainPartition the domain partition to which the service
     *                         belongs
     * @return the aggregated data of a service
     * @throws Exception in case of errors
     */
    public JsonNode getAggregatedServiceData(String sServiceName, String sDomainPartition)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sServiceName));
        if (sDomainPartition != null)
            {
            urlBuilder.addQueryParameter("domainPartition", sDomainPartition);
            }

        urlBuilder = urlBuilder.addQueryParameter("fields", "name,domainPartition,statusHA,partitionsAll," +
                                                            "partitionsEndangered,partitionsVulnerable,partitionsUnbalanced,requestPendingCount,storageEnabled," +
                                                            "memberCount").addQueryParameter("links", "");

        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the aggregated data for all the incoming(OriginMBean) members of a
     * federated service.
     *
     * @param sServiceName     the name of the service
     * @param sDomainPartition the domain partition to which the service
     *                         belongs
     * @return the aggregated data of incoming members
     * @throws Exception in case of errors
     */
    public JsonNode getAggregatedIncomingData(String sServiceName, String sDomainPartition)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sServiceName)).addPathSegment("federation").addPathSegment("statistics")
                .addPathSegment("incoming").addPathSegment("participants");

        if (sDomainPartition != null)
            {
            urlBuilder.addQueryParameter("domainPartition", sDomainPartition);
            }

        urlBuilder = urlBuilder.addQueryParameter("fields", "status,bytesReceivedSecs,msgsReceivedSecs")
                .addQueryParameter("links", "");

        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Get the aggregated data for all the outgoing(DestinationMBean) members of
     * a federated service.
     *
     * @param sServiceName     the name of the service
     * @param sDomainPartition the domain partition to which the service
     *                         belongs
     * @return the aggregated data of outgoing members
     * @throws Exception in case of errors
     */
    public JsonNode getAggregatedOutgoingData(String sServiceName, String sDomainPartition)
            throws Exception
        {
        URLBuilder urlBuilder = getBasePath().addPathSegment("services")
                .addPathSegment(encodeServiceName(sServiceName))
                .addPathSegment("federation").addPathSegment("statistics").addPathSegment("outgoing")
                .addPathSegment("participants");


        if (sDomainPartition != null)
            {
            urlBuilder.addQueryParameter("domainPartition", sDomainPartition);
            }

        urlBuilder = urlBuilder.addQueryParameter("fields", "status,bytesSentSecs,msgsSentSecs")
                .addQueryParameter("links", "");

        return getResponseJson(sendGetRequest(urlBuilder));
        }

    /**
     * Send a GET HTTP request and return the response, if valid.
     *
     * @param urlBuilder the URL builder of the URL
     * @return the response of the GET request
     * @throws IOException thrown in case of exceptions while connecting to the
     *                     REST server
     */
    private InputStream sendGetRequest(URLBuilder urlBuilder) throws Exception
        {
        long start = System.currentTimeMillis();
        URL url = urlBuilder.getUrl();
        java.net.HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        setHttpRequestTimeout(connection);
        boolean isRequestDebugEnabled = GlobalPreferences.sharedInstance().isRestDebugEnabled();

        int nResponseCode = connection.getResponseCode();
        if (nResponseCode != 200)
            {
            LOGGER.warning("Http request " + url.toString() + " returned error code " + nResponseCode);
            return null;
            }

        InputStream inputStream = connection.getInputStream();
        if (isRequestDebugEnabled)
            {
            LOGGER.info((System.currentTimeMillis() - start) + "ms to open connection to "
                        + urlBuilder.getUrl().toString() + " ");
            }

        return inputStream;
        }

    /**
     * Send a POST HTTP request and return the response, if valid.
     *
     * @param urlBuilder the URL builder of the URL
     * @return the response of the POST request
     * @throws IOException thrown in case of exceptions while connecting to the
     *                     REST server
     */
    private InputStream sendPostRequest(URLBuilder urlBuilder) throws Exception
        {
        return sendPostRequest(urlBuilder, null);
        }

    /**
     * Send a POST HTTP request and return the response, if valid.
     *
     * @param urlBuilder the URL builder of the URL
     * @param sRole      role to add to body
     * @return the response of the POST request
     * @throws IOException thrown in case of exceptions while connecting to the
     *                     REST server
     */
    private InputStream sendPostRequest(URLBuilder urlBuilder, String sRole)
            throws Exception
        {
        URL  url   = urlBuilder.getUrl();
        long start = System.currentTimeMillis();

        boolean isRequestDebugEnabled = GlobalPreferences.sharedInstance().isRestDebugEnabled();
        java.net.HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        connection.setRequestMethod("POST");
        setHttpRequestTimeout(connection);

        if (f_fisWebLogic)
            {
            connection.setRequestProperty(REQUESTED_BY, "JVisualVM");
            }

        // if we have a role parameter to send
        if (sRole != null)
            {
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            String sInput = "{\"role\": \"" + sRole + "\"}";
            try (OutputStream os = connection.getOutputStream())
                {
                byte[] abData = sInput.getBytes(StandardCharsets.UTF_8);
                os.write(abData, 0, abData.length);
                }
            }

        if (isRequestDebugEnabled)
            {
            LOGGER.info((System.currentTimeMillis() - start) + "ms to open POST to "
                        + urlBuilder.getUrl().toString() + " ");
            }

        int nResponseCode = connection.getResponseCode();

        if (nResponseCode != 200)
            {
            if (nResponseCode != 404)
                {
                JsonNode errorJson = getResponseJson(connection.getErrorStream());
                String sCause = errorJson != null
                                ? errorJson.toString()
                                : connection.getResponseMessage();
                throw new RuntimeException("Invalid Response Http Code: " + nResponseCode + ", \nCause: " + sCause);
                }
            else
                {
                throw new RuntimeException("Not Found " + url.toString());
                }
            }

        return connection.getInputStream();
        }

    /**
     * Send a DELETE HTTP request and return the response, if valid.
     *
     * @param urlBuilder the URL builder of the URL
     * @return the response of the DELETE request
     * @throws IOException thrown in case of exceptions while connecting to the
     *                     REST server
     */
    private InputStream sendDeleteRequest(URLBuilder urlBuilder)
            throws Exception
        {
        long start = System.currentTimeMillis();
        boolean isRequestDebugEnabled = GlobalPreferences.sharedInstance().isRestDebugEnabled();
        URL url = urlBuilder.getUrl();
        java.net.HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("DELETE");
        setHttpRequestTimeout(connection);

        if (f_fisWebLogic)
            {
            connection.setRequestProperty(REQUESTED_BY, "JVisualVM");
            }

        int nResponseCode = connection.getResponseCode();
        if (isRequestDebugEnabled)
            {
            LOGGER.info((System.currentTimeMillis() - start) + "ms to open DELETE to "
                        + urlBuilder.getUrl().toString() + " ");
            }

        if (nResponseCode != 200)
            {
            throw new RuntimeException("Invalid Response Response code: " + nResponseCode);
            }

        return connection.getInputStream();
        }

    /**
     * Sets the Http request timeout for a {@link URLConnection}.
     *
     * @param urlConnection {@link URLConnection} to set timeout for
     */
    private void setHttpRequestTimeout(URLConnection urlConnection)
        {
        int nRestTimeout = GlobalPreferences.sharedInstance().getRestTimeout();
        urlConnection.setConnectTimeout(nRestTimeout);
        urlConnection.setReadTimeout(nRestTimeout);
        }

    /**
     * Convert a name into a REST standards compatible name.
     *
     * @param name the service name to be normalized
     * @return the REST compatible name
     */
    private String getRestName(String name)
        {
        // find the first set of upper case letters
        int count = 0;
        for (; count < name.length(); count++)
            {
            if (!Character.isUpperCase(name.charAt(count)))
                {
                break;
                }
            }
        if (count == name.length())
            {
            // all upper case - leave it alone
            return name;
            }
        if (count == 0)
            {
            // doesn't start upper case - leave it alone
            return name;
            }
        if (count == 1)
            {
            // first letter is upper case and next letter is lower case, so convert
            // first letter to lower case
            // for example RefreshTime must be returned as refreshTime
            return name.substring(0, count).toLowerCase() + name.substring(count);
            }
        // starts with an acronym - leave it alone
        return name;
        }

    /**
     * Modify the URL based on the MBean ObjectName, so that the details of the
     * MBean can be retrieved using the URL. For example in order to get the
     * attributes of a cache MBean, the URL will be /services/{service-name}/caches/{cache-name}/members/{nodeID}
     *
     * @param objectName the ObjectName of the MBean
     * @param urlBuilder the builder for the URL
     * @return the modified URL
     */
    private URLBuilder modifyTarget(ObjectName objectName, URLBuilder urlBuilder)
        {
        switch (objectName.getKeyProperty("type"))
            {
            case "Node":
                return urlBuilder.addPathSegment("members")
                        .addPathSegment(getKeyPropertyFromObjName(objectName, "nodeId"))
                        .addQueryParameter("links", "");
            case "Journal":
                String sJournalUrlType = objectName.getKeyProperty("name").equals("FlashJournalRM")
                                         ? "flash" : "ram";
                return urlBuilder.addPathSegment("journal").addPathSegment(sJournalUrlType).addPathSegment("members")
                        .addPathSegment(getKeyPropertyFromObjName(objectName, "nodeId"));
            case "Cache":
                urlBuilder = urlBuilder.addPathSegment("services")
                        .addPathSegment(encodeServiceName(getKeyPropertyFromObjName(objectName, "service")))
                        .addPathSegment("caches").addPathSegment(getKeyPropertyFromObjName(objectName, "name"))
                        .addPathSegment("members")
                        .addPathSegment(getKeyPropertyFromObjName(objectName, "nodeId"))
                        .addQueryParameter("tier", getKeyPropertyFromObjName(objectName, "tier"));
                String loader = objectName.getKeyProperty("loader");
                if (loader != null)
                    {
                    urlBuilder = urlBuilder.addQueryParameter("loader", loader);
                    }
                return urlBuilder;
            case "StorageManager":
                return urlBuilder.addPathSegment("services")
                        .addPathSegment(encodeServiceName(getKeyPropertyFromObjName(objectName, "service")))
                        .addPathSegment("caches").addPathSegment(objectName.getKeyProperty("cache"))
                        .addPathSegment("members").addPathSegment(getKeyPropertyFromObjName(objectName, "nodeId"));
            case "Service":
                return urlBuilder.addPathSegment("services")
                        .addPathSegment(encodeServiceName(getKeyPropertyFromObjName(objectName, "name")))
                        .addPathSegment("members").addPathSegment(getKeyPropertyFromObjName(objectName, "nodeId"))
                        .addQueryParameter("links", "");
            case "ConnectionManager":
                return urlBuilder.addPathSegment("services")
                        .addPathSegment(encodeServiceName(getKeyPropertyFromObjName(objectName, "name")))
                        .addPathSegment("members").addPathSegment(getKeyPropertyFromObjName(objectName, "nodeId"))
                        .addPathSegment("proxy");
            case "Cluster":
                return urlBuilder;
            case "Persistence":
                return urlBuilder.addPathSegment("services")
                        .addPathSegment(encodeServiceName(getKeyPropertyFromObjName(objectName, "service")))
                        .addPathSegment("persistence");
            case "Platform":
                urlBuilder = urlBuilder.addPathSegment("members")
                        .addPathSegment(getKeyPropertyFromObjName(objectName, "nodeId"))
                        .addPathSegment("platform");
                String subType = objectName.getKeyProperty("subType");
                if (subType != null)
                    {
                    switch (subType)
                        {
                        case "OperatingSystem":
                            return urlBuilder.addPathSegment("operatingSystem");
                        }
                    }
            }
        return null;
        }

    /**
     * Get a key property from the ObjectName.
     *
     * @param objectName the ObjectName of the MBean
     * @param sKey       the key of the property
     * @return the value of the property in the MBean
     */
    protected String getKeyPropertyFromObjName(ObjectName objectName, String sKey)
        {
        return objectName.getKeyProperty(sKey);
        }

    /**
     * Red the HTTP response as a JSON body.
     *
     * @param stream the response stream
     * @return the JSON response
     * @throws IOException thrown in case of exceptions while connecting to the
     *                     REST server
     */
    protected JsonNode getResponseJson(InputStream stream) throws IOException
        {
        if (stream == null)
            {
            // return a null json node if there is no response
            return MissingNode.getInstance();
            }
        ObjectMapper mapper = new ObjectMapper();

        return mapper.readTree(stream);
        }

    /**
     * Get the base path for the management REST server.
     *
     * @return the modified URL builder
     */
    protected URLBuilder getBasePath()
        {
        String sUrl = f_sUrl;
        URLBuilder bldrURl = new URLBuilder(sUrl);

        // if the URL already contain management/coherence, no need to
        // modify it
        if (sUrl.contains("management/coherence"))
            {
            return f_fisWebLogic && m_sClusterName != null
                   ? bldrURl.addPathSegment(m_sClusterName)
                   : bldrURl;
            }

        // else append the Coherence specific parts to the URL
        bldrURl.addPathSegment("management").addPathSegment("coherence").addPathSegment("cluster");
        return bldrURl;
        }

    /**
     * Get the list of MBean ObjectNames from the provided response.
     *
     * @param streamInput the input stream from the response
     * @return the list of ObjectName
     * @throws IOException                  thrown in case of connectivity
     *                                      errors to REST server
     * @throws MalformedObjectNameException the exception thrown if the
     *                                      ObjectName is malformed
     */
    protected Set<ObjectName> getSetObjectNamesFromResponse(InputStream streamInput)
            throws IOException, MalformedObjectNameException
        {
        Set<ObjectName> setObjectNames = new HashSet<>();
        JsonNode rootNode = getResponseJson(streamInput);
        JsonNode nodeItems = rootNode.get("items");
        if (nodeItems != null && nodeItems.isArray())
            {
            for (int i = 0; i < nodeItems.size(); i++)
                {
                JsonNode nodeItem = nodeItems.get(i);
                Hashtable<String, String> mapKeysProps = new Hashtable<>();

                nodeItem.fields().forEachRemaining(e -> mapKeysProps.put(e.getKey(), e.getValue().asText()));
                setObjectNames.add(new ObjectName("Coherence", mapKeysProps));
                }
            }

        return setObjectNames;
        }

    /**
     * Indicates if the URL is valid.
     *
     * @return true if the URL is valid
     */
    public boolean isValidUrl()
        {
        try
            {
            InputStream stream = sendGetRequest(getBasePath());
            // if input stream is null, the URL is invalid
            return stream != null;
            }
        catch (Exception e)
            {
            // an exception here means that the URL is not valid.
            }
        return false;
        }

    /**
     * Returns the root node for a WebLogic server connection.
     *
     * @param rootNode the current root node
     * @return the new root node
     */
    private JsonNode getRootNodeForWebLogicServer(JsonNode rootNode)
        {
        ArrayNode itemsNode = (ArrayNode) rootNode.get("items");

        if (itemsNode != null && itemsNode.size() > 0)
            {
            return itemsNode.get(0);
            }
        return rootNode;
        }

    /**
     * Returns the cluster name.
     *
     * @return the cluster name.
     */
    public String getClusterName()
        {
        return m_sClusterName;
        }

    /**
     * Sets the cluster name.
     *
     * @param sClusterName cluster name
     */
    public void setClusterName(String sClusterName)
        {
        m_sClusterName = sClusterName;
        }

    /**
     * Encode a service name by stripping any double quotes.
     *
     * @param sServiceName service name to encode
     * @return encoded service name
     */
    private String encodeServiceName(String sServiceName)
        {
        return sServiceName.replaceAll("\"", "");
        }

    /**
     * Internal class to build a URL.
     */
    public static class URLBuilder
        {
        // ----- constructors ----------------------------------------------

        /**
         * Construct a {@link URLBuilder}.
         *
         * @param sBasePath base path
         */
        public URLBuilder(String sBasePath)
            {
            m_bldrUrl.append(sBasePath);
            }

        /**
         * Add a path segment.
         *
         * @param sPath path to add
         *
         * @return a {@link URLBuilder}
         */
        public URLBuilder addPathSegment(String sPath)
            {
            m_bldrUrl.append("/").append(sPath);
            return this;
            }

        /**
         * Add a query parameter.
         *
         * @param sKey  key
         * @param sValue value
         *
         * @return a {@link URLBuilder}
         */
        public URLBuilder addQueryParameter(String sKey, String sValue)
            {
            m_mapQueryParams.put(sKey, sValue);
            return this;
            }

        /**
         * Returns the {@link URL}.
         *
         * @return {@link URL}
         *
         * @throws Exception if any errors
         */
        public URL getUrl() throws Exception
            {
            StringBuilder completeUrl = new StringBuilder(m_bldrUrl);
            if (!m_mapQueryParams.isEmpty())
                {
                completeUrl.append("?");
                String sQueryParams = m_mapQueryParams.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining("&"));
                completeUrl.append(sQueryParams);
                }

            return new URL(completeUrl.toString());
            }

        /**
         * {@link StringBuilder} to build the url.
         */
        private final StringBuilder m_bldrUrl = new StringBuilder();

        /**
         * {@link Map} of query parameters.
         */
        private final Map<String, String> m_mapQueryParams = new HashMap<>();
        }

    /**
     * Initialize SSL.
     * @throws Exception if an SSL related exceptions.
     */
    private void initSSL()
            throws Exception {

        m_sslContext = SSLContext.getInstance("TLS");

        // disable SSL verification if option set
        if (GlobalPreferences.sharedInstance().isSSLCertValidationDisabled())
            {
            String sText =  getLocalText("LBL_ssl_validation_disabled");
            LOGGER.warning(sText);
            m_sslContext.init(null, TRUST_ALL_CERTS, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultHostnameVerifier(TRUST_ALL_HOSTS);
            JOptionPane.showMessageDialog(null, getLocalText("LBL_ssl_validation_disabled"), getLocalText("LBL_warning"),
                    JOptionPane.WARNING_MESSAGE);
            }
        else
            {
            m_sslContext.init(null, null, new java.security.SecureRandom());
            }

        HttpsURLConnection.setDefaultSSLSocketFactory(m_sslContext.getSocketFactory());
    }

    // ----- data members ---------------------------------------------------

    /**
     * The URL of the management REST server.
     */
    private final String f_sUrl;

    /**
     * Indicates if this REST endpoint is for WebLogic Server.
     */
    private final boolean f_fisWebLogic;

    /**
     * Indicates if SSL is being used for the connection.
     */
    private final boolean f_isSSl;

    /**
     * The discovered cluster name.
     */
    private String m_sClusterName;

    /**
     * The logger object to use.
     */
    private static final Logger LOGGER = Logger.getLogger(HttpRequestSender.class.getName());

    /**
     * Header required for POST and DELETE to WebLogic Server.
     */
    private static final String REQUESTED_BY = "X-Requested-By";

    /**
     * Prefix for cluster object name.
     */
    public static final String CLUSTER_PREFIX = "Coherence:type=Cluster,cluster=";

    /**
     * A trust manager that will trust all certificates. Only used when the preference to ignore SSL certs is chosen.
     * Should be used with care.
     */
    private static final TrustManager[] TRUST_ALL_CERTS =
        {
        new X509TrustManager()
            {
            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers()
                {
                return null;
                }

            @Override
            public void checkClientTrusted(X509Certificate[] certs, String authType)
                {
                }

            @Override
            public void checkServerTrusted(X509Certificate[] certs, String authType)
                {
                }
            }
        };

    /**
     * A {@link HostnameVerifier} to trust all hosts. Only used when the preference to ignore SSL certs is chosen.
     * Should be used with care.
     */
    private static final HostnameVerifier TRUST_ALL_HOSTS = new HostnameVerifier()
        {
        @Override
        public boolean verify(String hostname, SSLSession session)
            {
            return true;
            }
        };

    /**
     * SSL Context.
     */
    private SSLContext m_sslContext;
    }
