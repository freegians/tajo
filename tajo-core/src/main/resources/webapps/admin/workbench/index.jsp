<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
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

        <!-- Collect the nav links, forms, and other content for toggling -->
        <!--<c:if test="${tajoDesktop == false}">-->
        <div th:if="${tajoDesktop} == false">
            <div class="collapse navbar-collapse" id="bs-example-navbar-collapse-1">
                <ul class="nav navbar-nav">
                    <li class="dropdown">
                        <a href="#" class="dropdown-toggle" data-toggle="dropdown" role="button" aria-haspopup="true" aria-expanded="false">Workbook<span class="caret"></span></a>
                        <ul class="dropdown-menu">
                            <li><a href="#">Create new workbook</a></li>
                            <li role="separator" class="divider"></li>
                            <li><a href="#">Workbook Tutorial</a></li>

                        </ul>
                    </li>
                </ul>

                <ul class="nav navbar-nav navbar-right">
                    <li class="dropdown">
                        <a href="#" class="dropdown-toggle" data-toggle="dropdown" role="button" aria-haspopup="true" aria-expanded="false"><span th:text="${email}">${email}</span> <span class="caret"></span></a>
                        <ul class="dropdown-menu">
                            <li><a href="#">메뉴 추가 예정</a></li>
                            <li role="separator" class="divider"></li>
                            <li><a th:action="@{/logout}">Sign Out</a></li>
                        </ul>
                    </li>
                </ul>
                <div class="navbar-text navbar-right" th:text="${userGroupName}">${userGroupName}</div>

            </div><!-- /.navbar-collapse -->
            <!--</c:if>-->
        </div>
    </div><!-- /.container-fluid -->
</nav>




</body>
</html>