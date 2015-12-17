package org.apache.tajo.webapp;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.util.StringUtils;
import org.apache.tajo.client.v2.QueryFuture;
import org.apache.tajo.client.v2.TajoClient;
import org.apache.tajo.conf.TajoConf;
import org.apache.tajo.master.TajoMaster;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;

import javax.annotation.PreDestroy;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

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

public class WorkbenchRestServlet extends HttpServlet {
  private static final Log LOG = LogFactory.getLog(WorkbenchRestServlet.class);
  private static final long serialVersionUID = -1517586415463171579L;

  transient ObjectMapper om = new ObjectMapper();

  //queryRunnerId -> QueryRunner
  //TODO We must handle the session.

  private transient TajoConf tajoConf;
  private transient TajoMaster master = (TajoMaster) StaticHttpServer.getInstance().getAttribute("tajo.info.server.object");

  private transient TajoClient tajoClient2 = null;
  private transient TajoClient sampleReadTajoClient2 = null;

  private transient QueryFuture runQueryFuture = null;
  private transient ResultSet rs = null;

  private transient Map<String, ResultSet> tempResultSet = new HashMap<String, ResultSet>();
  private transient Map<String, QueryFuture> tempQueryFuture = new HashMap<String, QueryFuture>();


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


  private void writeObject(java.io.ObjectOutputStream stream) throws IOException {
    throw new NotSerializableException( getClass().getName() );
  }

  private void readObject(java.io.ObjectInputStream stream) throws IOException, ClassNotFoundException {
    throw new NotSerializableException( getClass().getName() );
  }

  @Override
  public void init(ServletConfig config) throws ServletException {
    om.getDeserializationConfig().disable(
        DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES);

    try {
//      tajoConf = new TajoConf();
//      tajoClient = new TajoClientImpl(ServiceTrackerFactory.get(tajoConf));

    } catch (Throwable e) {
      LOG.error(e.getMessage(), e);
    }
  }

  @Override
  public void service(HttpServletRequest request,
                      HttpServletResponse response) throws ServletException, IOException {
    String action = request.getParameter("action");
    String host = request.getParameter("host");
    String port = request.getParameter("port");
    String queryId = request.getParameter("queryId");
    String databaseName = request.getParameter("databaseName");
    String tableName = request.getParameter("tableName");

    Map<String, Object> returnValue = new HashMap<>();
    try {
//      if(tajoClient == null) {
//        errorResponse(response, "TajoClient not initialized");
//        return;
//      }
      if(action == null || action.trim().isEmpty()) {
        errorResponse(response, "no action parameter.");
        return;
      }

      if("connectTajoClient".equals(action)) {
        cleanup();
        tajoClient2 = new TajoClient(host, Integer.parseInt(port));
        sampleReadTajoClient2 = new TajoClient(host, Integer.parseInt(port));
        returnValue.put("data", true);
      } else if("getDatabaseNames".equals(action)) {
        returnValue.put("data", master.getCatalog().getAllDatabaseNames());
      }
      returnValue.put("success", "true");
      writeHttpResponse(response, returnValue);
    } catch (Exception e) {
      LOG.error(e.getMessage(), e);
      errorResponse(response, e);
    }
  }

  private void errorResponse(HttpServletResponse response, Exception e) throws IOException {
    errorResponse(response, e.getMessage() + "\n" + StringUtils.stringifyException(e));
  }

  private void errorResponse(HttpServletResponse response, String message) throws IOException {
    Map<String, Object> errorMessage = new HashMap<>();
    errorMessage.put("success", "false");
    errorMessage.put("errorMessage", message);
    writeHttpResponse(response, errorMessage);
  }

  private void writeHttpResponse(HttpServletResponse response, Map<String, Object> outputMessage) throws IOException {
    response.setContentType("text/html");

    OutputStream out = response.getOutputStream();
    out.write(om.writeValueAsBytes(outputMessage));

    out.flush();
    out.close();
  }

}
