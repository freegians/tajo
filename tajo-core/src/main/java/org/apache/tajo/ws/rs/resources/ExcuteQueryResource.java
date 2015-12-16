/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tajo.ws.rs.resources;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tajo.client.v2.QueryFuture;
import org.apache.tajo.client.v2.TajoClient;
import org.apache.tajo.client.v2.exception.ClientUnableToConnectException;
import org.apache.tajo.conf.TajoConf;

import javax.annotation.PreDestroy;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Deals with Database Management
 */
@Path("/excuteQuery")
public class ExcuteQueryResource {

  private static final Log LOG = LogFactory.getLog(ExcuteQueryResource.class);

  private transient TajoConf tajoConf;

  private TajoClient tajoClient2 = null;
  private TajoClient sampleReadTajoClient2 = null;

  private QueryFuture runQueryFuture = null;
  private ResultSet rs = null;

  private Map<String, ResultSet> tempResultSet = new HashMap<String, ResultSet>();
  private Map<String, QueryFuture> tempQueryFuture = new HashMap<String, QueryFuture>();


  @PreDestroy
  public void cleanup() {
    if (tajoClient2 != null) {
      tajoClient2.close();
      tajoClient2 = null;
    }
    if (sampleReadTajoClient2 != null) {
      sampleReadTajoClient2.close();
      sampleReadTajoClient2 = null;
    }
  }
  @PreDestroy
  public void futureCleanup() {
    if (runQueryFuture != null) {
      runQueryFuture.close();
      runQueryFuture = null;
    }
  }


  @GET
  @Path("/connect")
  public Object connectTajoClient2(
//          @PathParam("host") String host,
//          @PathParam("port") Integer port
  ) throws ClientUnableToConnectException {

    cleanup();
    tajoConf = new TajoConf();
    String cra = tajoConf.getVar(TajoConf.ConfVars.TAJO_MASTER_CLIENT_RPC_ADDRESS);
    String[] rpcAddress = cra.split(":");
    try {
      tajoClient2 = new TajoClient(rpcAddress[0], Integer.parseInt(rpcAddress[1]));
      sampleReadTajoClient2 = new TajoClient(rpcAddress[0], Integer.parseInt(rpcAddress[1]));
      return createSuccessResponse("success");
    } catch (Exception e) {
      return createFailureResponse("fail", e.getCause());
    }

  }

//  @GET
//  @Path("/database/getDatabaseNames")
//  public void selectDatabase(String databaseName) throws UndefinedDatabaseException {
//    tajoClient2.selectDB(databaseName);
//    sampleReadTajoClient2.selectDB(databaseName);
//  }

//  @GET
//  @Path("/{param}")
//  public Response printMessage(@PathParam("param") String msg) {
//
//    String result = "Restful example : " + msg;
//
//    return Response.status(200).entity(result).build();
//
//  }

  protected Response createSuccessResponse(Object data) {
    return createSuccessResponse("", data);
  }

  protected Response createSuccessResponse(String msg,  Object data) {
    Map<String, Object> result = new HashMap<String, Object>();
    result.put("success", Boolean.TRUE);
    result.put("data", data);
    result.put("msg", msg);
    return Response.status(200).entity(result).build();
  }

  protected Response createFailureResponse(String msg, Throwable e) {
    LOG.error(msg, e);
    Map<String, Object> result = new HashMap<String, Object>();
    result.put("success", Boolean.FALSE);
    result.put("msg", msg);
    return Response.status(200).entity(result).build();
  }


  protected Response createSuccessResponse(String msg, Object data, Object metaData) {
    Map<String, Object> result = new HashMap<String, Object>();
    result.put("success", Boolean.TRUE);
    result.put("data", data);
    result.put("metaData", metaData);
    result.put("msg", msg);
    return Response.status(200).entity(result).build();
  }

  protected Response createRetryResponse(String msg, Object data, Object metaData) {
    Map<String, Object> result = new HashMap<String, Object>();
    result.put("success", Boolean.TRUE);
    result.put("data", data);
    result.put("retry", true);
    result.put("metaData", metaData);
    result.put("msg", msg);
    return Response.status(200).entity(result).build();
  }
}
