<%
    /*
    * Licensed to the Apache Software Foundation (ASF) under one
    * or more contributor license agreements. See the NOTICE file
    * distributed with this work for additional information
    * regarding copyright ownership. The ASF licenses this file
    * to you under the Apache License, Version 2.0 (the
    * "License"); you may not use this file except in compliance
    * with the License. You may obtain a copy of the License at
    *
    * http://www.apache.org/licenses/LICENSE-2.0
    *
    * Unless required by applicable law or agreed to in writing, software
    * distributed under the License is distributed on an "AS IS" BASIS,
    * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    * See the License for the specific language governing permissions and
    * limitations under the License.
    */
%>
<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ page import="org.apache.tajo.conf.TajoConf" %>
<%@ page import="org.apache.tajo.master.TajoMaster" %>
<%@ page import="org.apache.tajo.service.ServiceTracker" %>
<%@ page import="org.apache.tajo.webapp.StaticHttpServer" %>
<%@ page import="java.net.InetSocketAddress" %>

<%
    TajoMaster master = (TajoMaster) StaticHttpServer.getInstance().getAttribute("tajo.info.server.object");
    TajoMaster.MasterContext context = master.getContext();
    TajoConf tajoConf = context.getConf();
    String masterAddress = tajoConf.getVar(TajoConf.ConfVars.TAJO_MASTER_INFO_ADDRESS);
    String[] mAddress = masterAddress.split(":");
    String cra = tajoConf.getVar(TajoConf.ConfVars.TAJO_MASTER_CLIENT_RPC_ADDRESS);
    String[] rpcAddress = cra.split(":");
    String restServicePort = tajoConf.getVar(TajoConf.ConfVars.REST_SERVICE_PORT);
    String restServiceAddress = rpcAddress[0] + ":" + restServicePort;

%>
<!DOCTYPE html>
<html>
<head lang="en">
    <title>Tajo</title>
    <%@ include file="script.jsp"%>
</head>
<body>
<nav id="gnb" class="navbar navbar-inverse navbar-fixed-top">
    <div class="container-fluid">
        <!-- Brand and toggle get grouped for better mobile display -->
        <div class="navbar-header">
            <button type="button" class="navbar-toggle collapsed" data-toggle="collapse" data-target="#bs-example-navbar-collapse-1" aria-expanded="false">
                <span class="sr-only">Toggle navigation</span>
                <span class="icon-bar"></span>
                <span class="icon-bar"></span>
                <span class="icon-bar"></span>
            </button>
            <a class="navbar-brand" href="#">Tajo Workbench</a>
        </div>


    </div><!-- /.container-fluid -->
</nav>

<div id="container" class="row">
    <div id="workbook-main" class="workbook-main col-xs-12 col-md-12">
        <div class="row">
            <div class="col-xs-9 col-md-9">
                <!--<h2>Tajo Workbench prototype</h2>-->
                <div id="tit-workbook-area"><input type="text" name="tit-workbook" id="tit-workbook" value="Welcome to Tajo-Workbench." readonly="" /></div>
                <br/>
                <div id="desc-workbook-area"><input name="desc-workbook" id="desc-workbook" row="3" value="You can run the code yourself. (Shift-Enter to Run)" readonly="" /></div>
                <!--<textarea name="desc-workbook" id="desc-workbook">Shift-Enter to Run</textarea>-->

            </div>
            <div class="col-xs-3 col-md-3">
                <h5 class="pull-right">
                    <span id="cluster-info"><%=rpcAddress[0]%>:<%=rpcAddress[1]%></span>
                </h5>
                <div style="clear: both"></div>
                <div class="dropdown pull-right" id="tables" style="margin-left: 5px;">
                    <button class="btn btn-default dropdown-toggle btn-xs" type="button" id="tablesDropdownMenu" data-toggle="dropdown" aria-haspopup="true" aria-expanded="true">
                        Tables
                        <span class="caret"></span>
                    </button>
                    <ul class="dropdown-menu" aria-labelledby="dropdownMenu1" id="table-list">

                    </ul>
                </div>

                <div class="dropdown pull-right" id="databases">
                    <button class="btn btn-default dropdown-toggle btn-xs" type="button" id="databasesDropdownMenu" data-toggle="dropdown" aria-haspopup="true" aria-expanded="true">
                        Databases
                        <span class="caret"></span>
                    </button>
                    <ul class="dropdown-menu" aria-labelledby="dropdownMenu1">
                        <% for (String databaseName : master.getCatalog().getAllDatabaseNames()) { %>
                        <li database-name="information_schema"><a href="#" class="database"><%=databaseName%></a></li>
                        <% } %>
                    </ul>
                </div>
                <!--<div style="clear: both"></div>-->
                <!--<div class="btn-group pull-right" role="group" aria-label="..." style="margin-top: 10px;">-->
                <!--<button type="button" class="btn btn-default btn-xs" data-toggle="tooltip" data-placement="top" title="Edit title & description">-->
                <!--<span class="glyphicon glyphicon-pencil" aria-hidden="true"></span>-->
                <!--</button>-->
                <!--<button type="button" class="btn btn-default btn-xs" data-toggle="tooltip" data-placement="top" title="Open workbook">-->
                <!--<span class="glyphicon glyphicon-folder-open" aria-hidden="true"></span>-->
                <!--</button>-->
                <!--<button type="button" class="btn btn-default btn-xs" data-toggle="tooltip" data-placement="top" title="Clone workbook">-->
                <!--<span class="fa fa-clone" aria-hidden="true"></span>-->
                <!--</button>-->
                <!--<button type="button" class="btn btn-default btn-xs" data-toggle="tooltip" data-placement="top" title="Share workbook">-->
                <!--<span class="fa fa-share-square-o" aria-hidden="true"></span>-->
                <!--</button>-->
                <!--<button type="button" class="btn btn-default btn-xs" data-toggle="tooltip" data-placement="top" title="Remove workbook">-->
                <!--<span class="fa fa-trash-o" aria-hidden="true"></span>-->
                <!--</button>-->
                <!--</div>-->


            </div>
        </div>
    </div>

    <div id="catalog-area" class="col-md-12 col-md-12">

        <div id="catalog-info">
            <div class="row">
                <div class="col-md-9">
                    <h4 id="catalog-table-name">Catalog</h4>
                </div>
                <div class="col-md-3">
                    <button type="button" id="btn-close-catalog" class="close" data-dismiss="modal" aria-label="Close"><span aria-hidden="true">×</span></button>
                </div>
            </div>
            <div class="row">
                <div class="col-md-10">
                    <!-- Nav tabs -->
                    <ul class="nav nav-tabs" role="tablist">
                        <li role="presentation" class="active"><a href="#sample-data-data" aria-controls="sample-data-data" role="tab" data-toggle="tab">Sample data</a></li>
                        <li role="presentation"><a href="#sample-data-column" aria-controls="sample-data-column" role="tab" data-toggle="tab">Column List</a></li>

                    </ul>
                    <!-- Tab panes -->
                    <div class="tab-content">
                        <div role="tabpanel" class="tab-pane active" id="sample-data-data">
                            <div class="table-example">
                                <table class="table">
                                    <thead>
                                    <tr>

                                    </tr>
                                    </thead>
                                    <tbody>


                                    </tbody>
                                </table>
                            </div>
                        </div>
                        <div role="tabpanel" class="tab-pane" id="sample-data-column">
                            <div class="column-list">
                                <table class="table">
                                    <tbody>

                                    </tbody>
                                </table>
                            </div>
                        </div>

                    </div>
                </div>
                <div class="col-md-2 sidebar" id="bx-catalog-table">
                    <ul class="nav nav-sidebar" id="bx-catalog-table-list">

                    </ul>
                </div>
            </div>

        </div>
    </div>

    <div id="timeline" class="col-xs-12 col-md-12">
        <div id="write-box">
            <div class="query-box">
                <div class="row">
                    <div class="col-xs-10 col-md-10">
                        <textarea id="txt-query" class="query active" spellcheck="false" onkeyup="textarea_resize(this)" placehoder="What would you like to do?"></textarea>
                    </div>
                    <div class="col-xs-2 col-md-2">
                        <div class="btn-group pull-right" role="group" aria-label="...">
                            <button type="button" id="btn-explain-query" class="btn btn-default btn-xs btn-explain-query" data-toggle="tooltip" data-placement="top" title="Explain task">
                                <span class="fa fa-list" aria-hidden="true"></span>
                            </button>
                            <button type="button" id="btn-run-query" class="btn btn-default btn-xs btn-run-query" data-toggle="tooltip" data-placement="top" title="Run task">
                                <span class="glyphicon glyphicon-play" aria-hidden="true"></span>
                            </button>
                        </div>
                        <div style="clear:both"></div>



                    </div>
                </div>
            </div>
        </div>
        <div id="result">

        </div>
    </div>
</div>

<script type="text/javascript">
    "use strict";
    <%--document.domain = '<%=rpcAddress[0]%>';--%>

    /*
     * IE7 이하 버전에서 "'console'이(가) 정의되지 않았습니다." 에러 처리
     */
    var console = console || {
                log:function(){},
                warn:function(){},
                error:function(){}
            };

    if(!ctx) var ctx = {};
    ctx = '//' + document.domain + ':<%=mAddress[1]%>/workbench_rest';

    <%--var _tajoDesktop = [[${tajoDesktop}]];--%>
    var _userId = [[${userId}]];
    var _email = [[${username}]];
    var _userGroupName = [[${userGroupName}]];
    var _role = [[${role}]];
    var _clusterName = '';
    var _clusterHost = '<%=rpcAddress[0]%>';
    var _clusterPort = '<%=rpcAddress[1]%>';
    var _connectDatabaseName = '';
    var _pollingTime = 5000;
    var _dataLimit = 100;
</script>

<script src="resources/js/app.js"></script>
<script src="resources/js/clusterInfo.js"></script>
<script src="resources/js/catalog.js"></script>
<script src="resources/js/timeline.js"></script>

<script>
    $(document).ready(function() {
//        defineClusterInfo(_clusterName, _clusterHost, _clusterPort);
        connectCluster();
        showDatabaseNames();
    });

</script>
</body>
</html>