/***************************
 * Cluster Info List
 ***************************/
$(document).ready(function() {
    //$('#tajoInfoModal').modal('show');
    //showClusterInfo();

    $('#btn-set-tajo-info').click(function() {
        $('#tajoInfoModal').modal('show');
        showClusterInfo();
    });
    $('#btn-submit-add-cluster').click(function() {
        var result = putAddClusterInfo();
        if(result.success) {
            showClusterInfo();
            $('#add-form-cluster-id').val('');
            $('#add-form-cluster-name').val('');
            $('#add-form-cluster-host').val('');
            $('#add-form-cluster-port').val('');
            $.bootstrapGrowl("Success!!", {
                type: 'success', // (null, 'info', 'danger', 'success')
                align: 'center',
                width: 'auto',
                delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
            });
        } else {
            $.bootstrapGrowl("### Error updating database.<br/> Could not get JDBC Connection", {
                type: 'danger', // (null, 'info', 'danger', 'success')
                align: 'center',
                width: 'auto',
                delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
            });
        }
    });

    $('#btn-submit-test-connection-cluster').click(function() {
        var result = testConnection();
        if(result.success) {
            $.bootstrapGrowl("Success!!", {
                type: 'success', // (null, 'info', 'danger', 'success')
                align: 'center',
                width: 'auto',
                delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
            });
        } else {
            var arr = result.msg.split("ClientConnectionException:");
            $.bootstrapGrowl(arr[1], {
                type: 'danger', // (null, 'info', 'danger', 'success')
                align: 'center',
                width: 'auto',
                delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
            });
        }
    });

    $('#btn-toggle-add-cluster-form').on('click', function() {
        $('#form-cluster input').val('');
        $('#add-form-cluster-name').focus();
    });

    $('#btn-tajoInfoModal-connect').click(function() {

        if($('#add-form-cluster-id').val()) {
            if(connectCluster()) {
                $('#cluster-info-name').html($('#add-form-cluster-name').val());
                $('#cluster-info').html($('#add-form-cluster-host').val() + ':' + $('#add-form-cluster-port').val());
                defineClusterInfo($('#add-form-cluster-name').val(), $('#add-form-cluster-host').val(), $('#add-form-cluster-port').val());
                showDatabaseNames();
            }

        }
    });
});

/**
 * 클러스터 정보 세팅하는 함수
 * @param clusterName
 * @param clusterHost
 * @param clusterPort
 */
function defineClusterInfo(clusterName, clusterHost, clusterPort) {
    _clusterName = clusterName;
    _clusterHost = clusterHost;
    _clusterPort = clusterPort;
}
/**
 * 클러스터 정보 가져오는 함수
 * @returns {*}
 */
function getClusterInfo() {
    var result;
    $.ajax({
        url: ctx + '/clusterInfo/' + _userId + '/list',
        dataType:'json',
        type:'get',
        async: false,
        success:function(res){
            result = res.data;
            if(res.success == false) {
                $.bootstrapGrowl("### Error querying database.<br/> Could not get JDBC Connection", {
                    type: 'danger', // (null, 'info', 'danger', 'success')
                    align: 'center',
                    width: 'auto',
                    delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
                });
            }
        }
    })
    return result;
}
/**
 * 클러스터 정보 modal 창에 보여주는 함수
 */
function showClusterInfo() {
    var result = getClusterInfo();
    var clusterStr = "";
    if(typeof result != undefined && result != '' && result !=null) {
        for (var i = 0; i < result.length; i++) {
            clusterStr += '<tr class="cluster-row" cluster-id="' + result[i].id + '">';
            //clusterStr += '     <td><input type="radio" name="cluster-address" class="radio-cluster-address" value="' + result[i].id + '" cluster-name="' + result[i].clusterName + '" cluster-host="' + result[i].host + '" cluster-port="' + result[i].port + '"></td>';
            clusterStr += '     <td class="cluster-name" cluster-id="' + result[i].id + '" cluster-name="' + result[i].clusterName + '" cluster-host="' + result[i].host + '" cluster-port="' + result[i].port + '">' + result[i].clusterName + '</td>';
            clusterStr += '     <td class="cluster-host">' + result[i].host + '</td>';
            clusterStr += '     <td class="cluster-port">' + result[i].port + '</td>';
            clusterStr += '     <td><button type="button" class="btn btn-default btn-xs btn-delete-cluster" cluster-id="' + result[i].id + '" data-toggle="tooltip" data-placement="top" title="Del Cluster"><span class="glyphicon glyphicon-remove" aria-hidden="true"></span></button></td>';
            clusterStr += '</tr>';
        }
    }
    $('#tajoInfoModal .modal-body table tbody').html(clusterStr);
    $('[data-toggle="tooltip"]').tooltip();

    if($('#add-form-cluster-id').val()) {
        $('#tajoInfoModal .modal-body table tbody tr.cluster-row[cluster-id=' + $('#add-form-cluster-id').val() + ']').addClass('active');
    }


    $('#tajoInfoModal .modal-body table tbody tr.cluster-row').each(function() {
        $(this).click(function() {
            $(this).parents('tbody').children('tr.cluster-row').removeClass('active');
            $(this).addClass('active');
            $('#add-form-cluster-id').val($(this).children('td.cluster-name').attr('cluster-id'));
            $('#add-form-cluster-name').val($(this).children('td.cluster-name').attr('cluster-name'));
            $('#add-form-cluster-host').val($(this).children('td.cluster-name').attr('cluster-host'));
            $('#add-form-cluster-port').val($(this).children('td.cluster-name').attr('cluster-port'));
        });
    });
    $('#tajoInfoModal .modal-body table tbody tr.cluster-row').each(function() {
        $(this).dblclick(function() {
            $(this).parents('tbody').children('tr.cluster-row').removeClass('active');
            $(this).addClass('active');
            $('#add-form-cluster-id').val($(this).children('td.cluster-name').attr('cluster-id'));
            $('#add-form-cluster-name').val($(this).children('td.cluster-name').attr('cluster-name'));
            $('#add-form-cluster-host').val($(this).children('td.cluster-name').attr('cluster-host'));
            $('#add-form-cluster-port').val($(this).children('td.cluster-name').attr('cluster-port'));

            $('#btn-tajoInfoModal-connect').trigger('click');
        });
    });

    $('#tajoInfoModal .modal-body table tbody .btn-delete-cluster').each(function() {
        $(this).click(function() {
            var ans = confirm("Can you remove this tajo cluster info?");
            if(ans) {
                var result = deleteClusterInfo($(this).attr('cluster-id'));
                if (result) {
                    showClusterInfo();
                    $.bootstrapGrowl("Success!!", {
                        type: 'success', // (null, 'info', 'danger', 'success')
                        align: 'center',
                        //width: 'auto',
                        delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
                    });
                } else {
                    $.bootstrapGrowl("### Error updating database.<br/> Could not get JDBC Connection", {
                        type: 'danger', // (null, 'info', 'danger', 'success')
                        align: 'center',
                        //width: 'auto',
                        delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
                    });
                }
            } else {
                return false;
            }
        });
    });

    $('#btn-submit-save-cluster').click(function() {
        var result = updateClusterInfo($('#add-form-cluster-id').val(), $('#add-form-cluster-name').val(), $('#add-form-cluster-host').val(), $('#add-form-cluster-port').val());
        if (result) {
            showClusterInfo();
            $('#tajoInfoModal .modal-body table tbody tr.cluster-row[cluster-id=' + $('#add-form-cluster-id').val() + ']').addClass('active');
            $.bootstrapGrowl("Success!!", {
                type: 'success', // (null, 'info', 'danger', 'success')
                align: 'center',
                width: 'auto',
                delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
            });
        } else {
            $.bootstrapGrowl("### Error updating database.<br/> Could not get JDBC Connection", {
                type: 'danger', // (null, 'info', 'danger', 'success')
                align: 'center',
                width: 'auto',
                delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
            });
        }
    });

}
/**
 * 클러스터 추가 하는 함수
 * @returns {*}
 */
function putAddClusterInfo() {
    var result;
    $.ajax({
        url: ctx + '/clusterInfo/' + $('#add-form-cluster-name').val() + '/' + $('#add-form-cluster-host').val() + '/' + $('#add-form-cluster-port').val() + '/' + _userId + '/set',
        dataType:'json',
        type:'put',
        async: false,
        success:function(res){
            result = res;
        }
    })
    return result;
}
/**
 * 클러스터 삭제 하는 함수
 * @param clusterId
 * @returns {*}
 */
function deleteClusterInfo(clusterId) {
    var result;
    $.ajax({
        url: ctx + '/clusterInfo/' + clusterId + '/delete',
        dataType:'json',
        type:'delete',
        async: false,
        success:function(res){
            result = res;
        }
    })
    return result;
}
/**
 * 클러스터 업데이트 하는 함수
 * @param clusterId
 * @param clusterName
 * @param host
 * @param port
 * @returns {*}
 */
function updateClusterInfo(clusterId, clusterName, host, port) {
    var result;
    $.ajax({
        url: ctx + '/clusterInfo/' + clusterId + '/' + clusterName + '/' + host + '/' + port + '/update',
        dataType:'json',
        type:'post',
        async: false,
        success:function(res){
            result = res;
        }
    })
    return result;
}
/**
 * 테스트 컨넥션
 * @returns {*}
 */
function testConnection() {
    var result;
    $.ajax({
        url: ctx + '/clusterInfo/' + $('#add-form-cluster-host').val() + '/' + $('#add-form-cluster-port').val() + '/testConnection',
        dataType:'json',
        type:'get',
        async: false,
        success:function(res){
            result = res;
        }
    });
    return result;
}

/**
 * 클러스터 연결 함수
 */
function connectCluster() {
    var result = true;
    //$.ajax({
    //    url: ctx + '/cluster/' + $('#add-form-cluster-host').val() + '/' + $('#add-form-cluster-port').val() + '/connectTajoClient1',
    //    dataType:'json',
    //    type:'post',
    //    async: false,
    //    success:function(res){
    //        if(res.success == false) {
    //            $.bootstrapGrowl(res.msg, {
    //                type: 'danger', // (null, 'info', 'danger', 'success')
    //                align: 'center',
    //                width: 'auto',
    //                delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
    //            });
    //            result = false;
    //        }
    //    }
    //});

    $.ajax({
        url: ctx,
        dataType:'json',
        type:'post',
        async: false,
        data: {
            action: 'connectTajoClient',
            host: _clusterHost,
            port: _clusterPort
        },
        success:function(res){
            if(res.success == false) {
                $.bootstrapGrowl(res.msg, {
                    type: 'danger', // (null, 'info', 'danger', 'success')
                    align: 'center',
                    width: 'auto',
                    delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
                });
                result = false;
            }
        }
    });
    return result;
}

function checkConnection() {
    var result;
    $.ajax({
        url: ctx + '/query/checkConnection',
        dataType:'json',
        type:'get',
        async: false,
        success:function(res){
            if(res.success == true) {
                if(res.data) {
                    result = true;
                } else {
                    result = false;
                }
            } else {
                result = false;
            }
        }
    });
    return result;
}
