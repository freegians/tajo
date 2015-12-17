/***************************
 * Database
 ***************************/
$(document).ready(function() {
    //setInterval(function() {
    //    if(_clusterHost) {
    //        //showDatabaseNames();
    //        //showTableList();
    //    }
    //}, _pollingTime);

    $('#btn-close-catalog').click(function() {
        $("#catalog-info" ).slideUp(500);
    });

});

/**
 * Gnb에 DB list 보여주는 함수
 */
function showDatabaseNames() {
    $('#databases ul').html('');
    var data = getDatabaseNames();

    var str = "";
    if(typeof data != undefined && data != '' && data !=null) {
        for (var i = 0; i < data.length; i++) {
            str += '<li database-name="' + data[i] + '"><a href="#" class="database">' + data[i] + '</a></li>'
        }
        str += '<li role="separator" class="divider"></li>';
        str += '<li><a href="#" id="btn-show-modal-create-database">Create database</a></li>';
        $('#databases ul').html(str);
    }

    $('#databases ul a.database').each(function() {
        $(this).click(function() {
            $('#databases button').html($(this).text() + ' <span class="caret"></span>');
            _connectDatabaseName = $(this).text();
            selectDatabase();
            showTableList();
            //$('#databases ul li').removeClass('disabled');
            //$('#databases ul li[database-name="' + $(this).text() + '"]').addClass('disabled');
        });
    });

    $('#btn-show-modal-create-database').click(function() {
        $('#modal-create-database').modal('show');

    });

    $('#btn-submit-create-database').click(function() {
        var result = createDatabase($('#input-create-database-name').val());
        if(result.success) {
            $.bootstrapGrowl("Success!!", {
                type: 'success', // (null, 'info', 'danger', 'success')
                align: 'center',
                width: 'auto',
                delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
            });
            $('#modal-create-database').modal('hide');
            showDatabaseNames($('#add-form-cluster-host').val(), $('#add-form-cluster-port').val());
        } else {
            $.bootstrapGrowl(result.msg, {
                type: 'danger', // (null, 'info', 'danger', 'success')
                align: 'center',
                width: 'auto',
                delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
            });
        }
    });
}

/**
 * DB list 가져오는 함수
 * @returns {*}
 */
function getDatabaseNames() {
    var result;
    $.ajax({
        url: ctx,
        dataType:'json',
        type:'get',
        async: false,
        data: {
            action: 'getDatabaseNames'
        },
        success:function(res){
            result = res.data;
        }
    });

    return result;
}

/**
 * DB 생성하는 함수
 * @param databaseName
 * @returns {*}
 */
function createDatabase(databaseName) {
    var result;
    $.ajax({
        url: ctx + '/database/' + databaseName + '/createDatabase',
        dataType:'json',
        type:'post',
        async: false,
        success:function(res){
            result = res;
        }
    });

    return result;
}

/**
 * DB 연결하는 함수
 * @returns {*}
 */
function selectDatabase() {
    var result;
    //$.ajax({
    //    url: ctx + '/cluster/' + _connectDatabaseName + '/selectDatabase',
    //    dataType:'json',
    //    type:'post',
    //    async: false,
    //    success:function(res){
    //        result = res;
    //    }
    //});
    $.ajax({
        url: ctx,
        dataType:'json',
        type:'post',
        async: false,
        data: {
            action: 'selectDatabase',
            databaseName: _connectDatabaseName
        },
        success:function(res){
            result = res;
        }
    });

    return result;
}

/**
 * Table List 보여주는 함수
 * @returns {boolean}
 */
function showTableList() {
    if(!_connectDatabaseName) {
        return false;
    }
    var data = getTableList();
    var str = '';
    for(var i = 0; i < data.length; i++) {
        str += '<li table-name="' + data[i] + '" table-no="' + i + '"><a href="#">' + data[i] + '</a></li>';
    }
    $('#table-list').html(str);
    $('#bx-catalog-table-list').html(str);

    $('#table-list li a').click(function() {
        $("#catalog-info").slideDown(500);

        $('#bx-catalog-table-list li').removeClass('active');
        $('#bx-catalog-table-list li[table-name="' + $(this).parents('li').attr('table-name') + '"]').addClass('active');

        var no = $('#bx-catalog-table-list li[table-name="' + $(this).parents('li').attr('table-name') + '"]').attr('table-no');

        var itemHeight = 38;
        var noHeight = no * itemHeight;
        if(noHeight >= 228) {   // 6 * 38
            $('#bx-catalog-table').scrollTop(itemHeight * (no - 5));
        }
        if(no < 6) {
            $('#bx-catalog-table').scrollTop(0);
        }

        showSampleData();
    });

    $('#bx-catalog-table-list li a').each(function() {
        $(this).click(function() {
            $('#bx-catalog-table-list li').removeClass('active');
            $(this).parents('li').addClass('active');

            showSampleData();
        });
    });
}

/**
 * Table List 가져오는 함수
 * @returns {*}
 */
function getTableList() {
    var result;
    $.ajax({
        url: ctx,
        dataType:'json',
        type:'get',
        async: false,
        data: {
            action: 'getTableList',
            databaseName: _connectDatabaseName
        },
        success:function(res){
            result = res.data;
        }
    });

    return result;
}

/**
 * sample data 보여주는 함수
 */
function showSampleData() {
    var data = getSampleData();

    var columnStr = "";
    var columnNameStr = '<tr><th>Column name</th>';
    var columnTypeStr = '<tr><th>Type</th>';
    for(var i = 0; i < data.metadata.columnMetadata.length; i++) {
        columnStr += '<th>' + data.metadata.columnMetadata[i].columnName + '</th>'
        columnNameStr += '<td>' + data.metadata.columnMetadata[i].columnName + '</td>';
        columnTypeStr += '<td>' + data.metadata.columnMetadata[i].columnType + '</td>';
    }
    columnNameStr += '</tr>';
    columnTypeStr += '</tr>';



    $('#sample-data-data table thead tr').html(columnStr);
    $('#sample-data-column table tbody').html(columnNameStr + columnTypeStr);

    var dataStr = "";
    for(var i = 0; i < data.data.length; i++) {
        dataStr += '<tr>';
        for(var j = 0; j < data.data[i].length; j++) {
            dataStr += '<td>' + data.data[i][j] + '</td>';
        }
        dataStr += '</tr>';
    }
    $('#sample-data-data table tbody').html(dataStr);

    $('#sample-data-data div.table-example').scrollTop(0);


    var tableName = $('#bx-catalog-table-list li[class~=active]').attr('table-name');
    $('#catalog-table-name').html($('#databasesDropdownMenu').text().slice(0,-1) + '.' + tableName);

}

/**
 * Sample data 가져오는 함수
 * @returns {*}
 */
function getSampleData() {
    var tableName = $('#bx-catalog-table-list li[class~=active]').attr('table-name');

    var result;
    $.ajax({
        url: ctx + '/query/' + _connectDatabaseName + '/' + tableName + '/100/getSampleData',
        dataType:'json',
        type:'get',
        async: false,
        success:function(res){
            result = res.data;
        }
    });

    return result;
}
