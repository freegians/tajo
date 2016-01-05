package org.apache.tajo.webapp;

import com.google.protobuf.ServiceException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.util.StringUtils;
import org.apache.tajo.client.v2.QueryFuture;
import org.apache.tajo.client.v2.TajoClient;
import org.apache.tajo.conf.TajoConf;
import org.apache.tajo.exception.TajoException;
import org.apache.tajo.jdbc.TajoMemoryResultSet;
import org.apache.tajo.master.TajoMaster;
import org.apache.tajo.webapp.workbench.ColumnMetadata;
import org.apache.tajo.webapp.workbench.QueryStatus;
import org.apache.tajo.webapp.workbench.ResultDataSet;
import org.apache.tajo.webapp.workbench.ResultMetadata;
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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

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
    String query = request.getParameter("query");
    String databaseName = request.getParameter("databaseName");
    String tableName = request.getParameter("tableName");
    String limit = request.getParameter("limit");

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

      } else if("selectDatabase".equals(action)) {
        tajoClient2.selectDB(databaseName);
        sampleReadTajoClient2.selectDB(databaseName);
        returnValue.put("data", true);
      } else if("getTableList".equals(action)) {
        returnValue.put("data", master.getCatalog().getAllTableNames(databaseName));
      } else if("getSampleData".equals(action)) {
        StringBuilder sb = new StringBuilder();
        sb.append("select * from ")
                .append(databaseName)
                .append(".")
                .append(tableName)
                .append(" limit ")
                .append(limit);
        try (QueryFuture future = sampleReadTajoClient2.executeQueryAsync(sb.toString())) {

          LOG.info("progress: " + future.progress());
          while (!future.isDone()) { // isDone will be true if query state becomes success, failed, or killed.
            LOG.info("progress: " + future.progress());
          }

          if (future.isSuccessful()) {

            ResultSet rs = future.get();
            ResultDataSet rds = getQueryResult(rs, Integer.parseInt(limit));
            rs.close();
//            return rds;
            returnValue.put("data", rds);
          }
        }

      } else if("checkConnection".equals(action)) {
        TajoClient tajoClient = tajoClient2;
        if(tajoClient == null) {
          returnValue.put("data", false);
        }
        returnValue.put("data", true);
      } else if("runQuery".equals(action)) {
        QueryStatus queryStatus = new QueryStatus();
        try {
          //futureCleanup();

          runQueryFuture = tajoClient2.executeQueryAsync(query);
          tempQueryFuture.put(runQueryFuture.id(), runQueryFuture);
          queryStatus.setId(runQueryFuture.id());
          returnValue.put("data", queryStatus);

        } catch (Exception e) {
          returnValue.put("data", e.getMessage());
        }
      } else if("queryStatus".equals(action)) {
        QueryStatus queryStatus = new QueryStatus();
        try {
          QueryFuture queryFuture = tempQueryFuture.get(queryId);
          queryStatus.setId(queryFuture.id());
          queryStatus.setState(queryFuture.state());
          queryStatus.setProgress(queryFuture.progress());
          queryStatus.setStartTime(queryFuture.startTime());
          queryStatus.setFinishTime(queryFuture.finishTime());
          if (queryFuture.isSuccessful()) {
            rs = queryFuture.get();
            tempResultSet.put(queryFuture.id(), rs);
          }
          returnValue.put("data", queryStatus);
        } catch (Exception e) {
          returnValue.put("data", e.getMessage());
        }

      } else if("getQueryResult".equals(action)) {
        ResultDataSet rds = getQueryResult(tempResultSet.get(queryId), Integer.parseInt(limit));
        returnValue.put("data", rds);
      } else if("explainQuery".equals(action)) {
        String trimedQuery = org.apache.commons.lang.StringUtils.trim(query);
        if(!org.apache.commons.lang.StringUtils.startsWithIgnoreCase(trimedQuery, "explain")) {
          trimedQuery = "explain " + trimedQuery;
        }

        ResultSet explanRs = null;
        StringBuilder sb = new StringBuilder();

        try (QueryFuture future = sampleReadTajoClient2.executeQueryAsync(trimedQuery)) {

          LOG.info("progress: " + future.progress());
          while (!future.isDone()) { // isDone will be true if query state becomes success, failed, or killed.
            LOG.info("progress: " + future.progress());
          }

          if (future.isSuccessful()) {
            explanRs = future.get();

          }

          while(explanRs.next()) {
            sb.append(explanRs.getString(1)).append("\n");
          }
          explanRs.close();

        } catch (TajoException e) {
          // executeQueryAsync() directly throws a TajoException instance if a query syntax is wrong.
          returnValue.put("data", e.getMessage());
        } catch (ExecutionException e) {
          returnValue.put("data", e.getMessage());
        } catch (Throwable t) {
          returnValue.put("data", t.getMessage());
        }
        returnValue.put("data", sb.toString());
      } else if("killQuery".equals(action)) {
        try {
          QueryFuture queryFuture = tempQueryFuture.get(queryId);
          if (!queryFuture.isDone()) {
            queryFuture.kill();
          }
          returnValue.put("data", "success");
        } catch (Exception e) {
          returnValue.put("data", e.getMessage());
        }
      }

      returnValue.put("success", true);
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
    errorMessage.put("success", false);
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



  private ResultDataSet getQueryResult(ResultSet rs, int limit) throws ServiceException, IOException, SQLException {
    if (rs == null) {
      return null;
    }
    ResultMetadata resultMetadata = getResultSetMetadata(rs);
    String[][] rows = getDataFromResultSet(rs, limit);

    ResultDataSet rds = new ResultDataSet()
            .withMetadata(resultMetadata)
            .withData(rows);

    return rds;
  }
  private ResultMetadata getResultSetMetadata(ResultSet resultSet) throws SQLException {
    List<ColumnMetadata> columnMetadatas = new ArrayList<ColumnMetadata>();
    if(resultSet instanceof TajoMemoryResultSet) {
    }
    ResultSetMetaData rsmd = resultSet.getMetaData();
    int columnCount = rsmd.getColumnCount();
    for (int i = 1; i <= columnCount; i++) {
      columnMetadatas.add(new ColumnMetadata()
              .withColumnName(rsmd.getColumnName(i))
              .withColumnType(rsmd.getColumnTypeName(i)));
    }
    ResultMetadata resultMetadata = new ResultMetadata()
            .withColumnMetadata(columnMetadatas);
    return resultMetadata;
  }
  private String[][] getDataFromResultSet(ResultSet resultSet, int limit) throws SQLException {
    List<String[]> rows = new ArrayList<String[]>();
    int columnCount = resultSet.getMetaData().getColumnCount();
    int limitCount = 0;
    int currentRow = 0;
    if(limit == -1) {		// set row position for download excel
      currentRow = resultSet.getRow();
      resultSet.beforeFirst();

    }
    while(resultSet.next()) {
      String[] columnData = new String[columnCount];
      for (int i = 1; i <= columnCount; i++) {
        columnData[i-1] = String.valueOf(resultSet.getObject(i));
      }
      rows.add(columnData);
      if(limit != -1) {
        if(++limitCount == limit) {
          break;
        }
      }
    }
    if(limit == -1) {		// reset row position after download excel
      resultSet.beforeFirst();
      for(int i = 0; i < currentRow; i++) {
        resultSet.next();
      }
    }
    return rows.toArray(new String[rows.size()][]);
  }

}
