/***************************
 * TimeLine
 ***************************/
var _uid = null;            // task id 를 임시로 저장
var _uidToQueryId = [];     // task id 와 queryId를 저장

var _chart = [];            // task id로 chart 저장
var _vis = [];              // task id로 d3 저장
var _d3 = [];
var _chartData = [];        // task id로 data 저장
var _chartSettingXAxis = [];    // task id로 xAxis 저장
var _chartSettingYAxis = [];    // task id로 yAxis 저장
var _chartType = null;          // chart type 임시 저장
var _blockQuery = false;



$(document).ready(function() {

    // shift + enter 단축키 설정
    $('#txt-query').each(function() {
        $(this).keydown(function(e) {
            if (e.keyCode == 13 && e.shiftKey && e.altKey) {
                e.preventDefault();
                $('#btn-explain-query').trigger('click');
            } else if (e.keyCode == 13 && e.shiftKey) {
                e.preventDefault();
                $('#btn-run-query').trigger('click');
            }

        });

    });

});

// query 실행
$('#btn-run-query').click(function() {
    var selectStr = $('#txt-query').selection();
    if(selectStr.length) {
        _blockQuery = true;
        runQuery(selectStr, true);
    } else {
        runQuery($('#txt-query').val(), true);
        _blockQuery = false;
    }
    $('#txt-query').focus();
});

// explain query
$('#btn-explain-query').click(function() {
    var selectStr = $('#txt-query').selection();
    if(selectStr.length) {
        explainQuery(selectStr);
    } else {
        explainQuery($('#txt-query').val());
    }
    $('#txt-query').focus();
});

function explainQuery(query) {
    var result;
    $.ajax({
        url: ctx + '/query/explain',
        dataType:'json',
        type:'get',
        async: false,
        data: {
            query: query

        },
        success:function(res){
            result = res;
            $('#modal-explain-query').modal('show');
            $('#modal-explain-query-body').html('<pre>' + res.data + '</pre>');
        }
    });
}

function killQuery() {
    $.ajax({
        url: ctx + '/query/kill',
        dataType:'json',
        type:'post',
        async: true,
        success:function(res){
        }
    });
}
function killQueryById(queryId) {
    $.ajax({
        url: ctx + '/query/kill/' + queryId,
        dataType:'json',
        type:'post',
        async: true,
        success:function(res){
        }
    });
}

/**
 * query 실행하는 함수
 * @param query
 * @param newTask
 * @param __uid
 */
function runQuery(query, newTask, __uid) {
    var checkCon = checkConnection();
    if(checkCon == false) {
        bootbox.confirm("connection closed by "+_clusterHost+":"+_clusterPort+"!!<br/>reconnect to "+_clusterHost+":"+_clusterPort+"?", function(result) {
            if(result) {
                if(connectCluster()) {
                    excuteQuery(query, newTask, __uid);
                } else {
                    return false;
                }
            }
        });
    } else {
        excuteQuery(query, newTask, __uid);
    }

}
function excuteQuery(query, newTask, __uid) {
    var result;
    $.ajax({
        url: ctx + '/query/query',
        dataType:'json',
        type:'post',
        async: false,
        data: {
            query: query

        },
        success:function(res){
            result = res;
            if(res.success == true) {
                if(newTask) {
                    var uid = showResultBox();
                    if(!_blockQuery) {
                        $('#txt-query').val('');               // query 실행후 작성했던 쿼리 삭제
                    }
                    $('#txt-query').css('height', '50px');  // query 실행후 textarea 크기 조정
                    getQueryStatus(uid);
                    _uidToQueryId[uid] = res.data.id;
                } else {
                    var uid = resetResultBox(__uid);
                    _uidToQueryId[uid] = res.data.id;
                    getQueryStatus(uid);
                }
                console.log('>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> query id: ', res.data.id);
            } else {
                if(typeof res.data != undefined && res.data != '' && res.data !=null) {
                    $.bootstrapGrowl(res.data.errorMessage, {
                        type: 'danger', // (null, 'info', 'danger', 'success')
                        align: 'center',
                        width: 'auto',
                        delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
                    });
                } else {
                    $.bootstrapGrowl(res.msg, {
                        type: 'danger', // (null, 'info', 'danger', 'success')
                        align: 'center',
                        width: 'auto',
                        delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
                    });
                }
            }
        }
    });
}
/** successfully submitted
    SCHEDULED,
/** Running
    RUNNING,
/** Error before a query execution
    ERROR,
/** Failure after a query launches
    FAILED,
/** Killed
    KILLED,
/** Wait for completely kill
    KILLING,
/** Successfully completed
    COMPLETED
 */

/**
 * query status 가져오는 함수
 * @param uid
 */
function getQueryStatus(uid) {
    var result;
    var stv = setInterval(function() {
        $.ajax({
            url: ctx + '/query/status/' + _uidToQueryId[uid],
            dataType:'json',
            type:'get',
            async: false,
            success:function(res){
                progressbar(uid, Math.round(res.data.progress * 100), res.data.state);
                if(res.data.state == "COMPLETED") {
                    clearInterval(stv);
                    showQueryResult(uid);
                    var runTime = ((res.data.finishTime - res.data.startTime) / 1000).toFixed(2);
                    var startTime = res.data.startTime;
                    showStartTime(uid, startTime);
                    showRunTime(uid, runTime + 's');
                    $('.btn-run-query').removeClass('disabled');
                } else if(res.data.state == "KILLED" || res.data.state == "ERROR" || res.data.state == "FAILED") {
                    clearInterval(stv);
                    progressbar(uid, 100, res.data.state);
                    $('.btn-run-query').removeClass('disabled');
                }
            }
        });
    }, 1000);
}

function showQueryResult(uid) {
    var data = getQueryResultId(uid);
    _chartData[uid] = data;

    var columnStr = "";
    var buttonStr = "";
    if(typeof data != undefined && data != '' && data !=null) {
        for (var i = 0; i < data.metadata.columnMetadata.length; i++) {
            columnStr += '<th>' + data.metadata.columnMetadata[i].columnName + '</th>'
            buttonStr += '<button type="button" class="btn btn-default btn-xs allColumn ui-draggable ui-draggable-handle" aria-haspopup="true" aria-expanded="false">' + data.metadata.columnMetadata[i].columnName + '</button>';
        }
        $('#' + uid + ' div.query-result-box div.query-result-grid table thead tr').html(columnStr);
        $('#' + uid + ' .chart-columns-select-area .chart-columns').html(buttonStr);
        setDragDrop(uid);

        var dataStr = "";
        for (var i = 0; i < data.data.length; i++) {
            dataStr += '<tr>';
            for (var j = 0; j < data.data[i].length; j++) {
                dataStr += '<td>' + data.data[i][j] + '</td>';
            }
            dataStr += '</tr>';
        }
        $('#' + uid + ' div.query-result-box div.query-result-grid table tbody').html(dataStr);
        $('#' + uid + ' div.query-result-box div.query-result-grid').show();

        $.bootstrapSortable(true, 'reversed');
    }
}
function getQueryResult() {
    var result;
    $.ajax({
        url: ctx + '/query/query',
        dataType:'json',
        type:'get',
        async: false,
        data: {
            limit: _dataLimit
        },
        success:function(res){
            result = res.data;
        }
    });
    return result;
}
function getQueryResultId(uid) {
    var result;
    $.ajax({
        url: ctx + '/query/queryId',
        dataType:'json',
        type:'get',
        async: false,
        data: {
            queryId: _uidToQueryId[uid],
            limit: _dataLimit
        },
        success:function(res){
            result = res.data;
            if(res.success == false) {
                $.bootstrapGrowl(res.msg, {
                    type: 'danger', // (null, 'info', 'danger', 'success')
                    align: 'center',
                    width: 'auto',
                    delay: 4000 // Time while the message will be displayed. It's not equivalent to the *demo* timeOut!
                });
            }
        }
    });
    return result;
}

function showQueryResultMore(uid) {
    var data = getQueryResultId(uid);

    var dataStr = "";
    if(typeof data != undefined && data != '' && data !=null) {
        for (var i = 0; i < data.data.length; i++) {
            dataStr += '<tr>';
            for (var j = 0; j < data.data[i].length; j++) {
                dataStr += '<td>' + data.data[i][j] + '</td>';
            }
            dataStr += '</tr>';
        }
    }
    $('#'+ uid + ' div.query-result-box div.query-result-grid table tbody tr').last().after(dataStr);
    $.bootstrapSortable(true, 'reversed');
    _chartData[uid].data = _chartData[uid].data.concat(data.data);
}

function showResultBox(query, height) {
    var query = null;
    var selectStr = $('#txt-query').selection();
    if(selectStr.length) {
        query = selectStr;
    } else {
        query = $('#txt-query').val();
    }

    var queryHeight = $('#txt-query').height();

    var uid = guid();
    _uid = uid;

    var resultBoxStr = "";

    resultBoxStr += '<div class="result-box" id="' + uid + '">';
    resultBoxStr += '   <div class="query-box">';
    resultBoxStr += '       <div class="row">';
    resultBoxStr += '           <div class="col-xs-9 col-md-9">';
    resultBoxStr += '               <textarea class="query" spellcheck="false" disabled onkeyup="textarea_resize(this)" placeholder="What would you like to do?" style="height:' + queryHeight + 'px;">' + query + '</textarea>';
    resultBoxStr += '           </div>';
    resultBoxStr += '           <div class="col-xs-3 col-md-3">';
    resultBoxStr += '               <div class="btn-group pull-right" role="group" aria-label="...">';
    resultBoxStr += '                   <button type="button" class="btn btn-default btn-xs btn-kill-query task" data-toggle="tooltip" data-placement="top" title="Kill task">';
    resultBoxStr += '                       <span class="fa fa-times" aria-hidden="true"></span>';
    resultBoxStr += '                   </button>';
    resultBoxStr += '                   <button type="button" class="btn btn-default btn-xs btn-run-query task" data-toggle="tooltip" data-placement="top" title="Run task">';
    resultBoxStr += '                       <span class="glyphicon glyphicon-play" aria-hidden="true"></span>';
    resultBoxStr += '                   </button>';
    resultBoxStr += '                   <button type="button" class="btn btn-default btn-xs btn-edit-query" data-toggle="tooltip" data-placement="top" title="Edit task">';
    resultBoxStr += '                       <span class="glyphicon glyphicon-pencil" aria-hidden="true"></span>';
    resultBoxStr += '                   </button>';
    //resultBoxStr += '                   <button type="button" class="btn btn-default btn-xs btn-open-page" data-toggle="tooltip" data-placement="top" title="Open page">';
    //resultBoxStr += '                       <span class="glyphicon glyphicon-folder-open" aria-hidden="true"></span>';
    //resultBoxStr += '                   </button>';
    //resultBoxStr += '                   <button type="button" class="btn btn-default btn-xs btn-clone-page" data-toggle="tooltip" data-placement="top" title="Clone page">';
    //resultBoxStr += '                       <span class="fa fa-clone" aria-hidden="true"></span>';
    //resultBoxStr += '                   </button>';
    //resultBoxStr += '                   <button type="button" class="btn btn-default btn-xs btn-share-page" data-toggle="tooltip" data-placement="top" title="Share page">';
    //resultBoxStr += '                       <span class="fa fa-share-square-o" aria-hidden="true"></span>';
    //resultBoxStr += '                   </button>';
    resultBoxStr += '                   <button type="button" class="btn btn-default btn-xs btn-close-result-box" data-toggle="tooltip" data-placement="top" title="Remove task">';
    resultBoxStr += '                       <span class="fa fa-trash-o" aria-hidden="true"></span>';
    resultBoxStr += '                   </button>';
    resultBoxStr += '               </div>';
    resultBoxStr += '               <div style="clear:both"></div>';
    resultBoxStr += '               <div class="progress">';
    resultBoxStr += '                   <div class="progress-bar progress-bar-info progress-bar-striped active" role="progressbar" aria-valuenow="60" aria-valuemin="0" aria-valuemax="100" style="width: 0%">';
    resultBoxStr += '                       0%';
    resultBoxStr += '                   </div>';
    resultBoxStr += '               </div>';
    resultBoxStr += '               <div style="clear:both"></div>';
    resultBoxStr += '               <div class="query-time">';
    //resultBoxStr += '                   <span class="start-time"></span><br><span class="run-time" style="display:none">Run-time: <span class="time"></span></span>';
    resultBoxStr += '                   <span class="start-time"></span><br><span class="run-time"><span class="time"></span></span>';
    resultBoxStr += '               </div>';
    resultBoxStr += '           </div>';
    resultBoxStr += '       </div>';
    resultBoxStr += '   </div>';
    resultBoxStr += '   <div class="btn-group" role="group" aria-label="...">';
    resultBoxStr += '       <button type="button" class="btn btn-default btn-xs active btn-show-result btn-show-result-grid">';
    resultBoxStr += '           <span class="glyphicon glyphicon-th" aria-hidden="true"></span>';
    resultBoxStr += '       </button>';
    resultBoxStr += '       <button type="button" class="btn btn-default btn-xs btn-show-result btn-show-result-chart-bar">';
    resultBoxStr += '           <span class="fa fa-bar-chart" aria-hidden="true"></span>';
    resultBoxStr += '       </button>';
    resultBoxStr += '       <button type="button" class="btn btn-default btn-xs btn-show-result btn-show-result-chart-line">';
    resultBoxStr += '           <span class="fa fa-line-chart" aria-hidden="true"></span>';
    resultBoxStr += '       </button>';
    resultBoxStr += '       <button type="button" class="btn btn-default btn-xs btn-show-result btn-show-result-chart-area">';
    resultBoxStr += '           <span class="fa fa-area-chart" aria-hidden="true"></span>';
    resultBoxStr += '       </button>';
    resultBoxStr += '       <button type="button" class="btn btn-default btn-xs btn-show-result btn-show-result-chart-pie">';
    resultBoxStr += '           <span class="fa fa-pie-chart" aria-hidden="true"></span>';
    resultBoxStr += '       </button>';
    resultBoxStr += '       <button type="button" class="btn btn-default btn-xs btn-show-result btn-show-result-chart-scatter">';
    resultBoxStr += '           <li class="gruter-scatter-chart-14" aria-hidden="true"></li>';
    resultBoxStr += '       </button>';
    //resultBoxStr += '       <button type="button" class="btn btn-default btn-xs" data-toggle="button" aria-pressed="false" autocomplete="off">';
    //resultBoxStr += '           <span class="fa fa-cog" aria-hidden="true"></span>';
    //resultBoxStr += '       </button>';
    resultBoxStr += '   </div>';
    resultBoxStr += '   <a href="#none" class="btn-chart-setting-toggle">';
    resultBoxStr += '           settings <span class="fa fa-caret-up"></span>';
    resultBoxStr += '   </a>';
    resultBoxStr += '   <a href="#none" class="btn-download-chart-image">';
    resultBoxStr += '           download PNG image <span class="fa fa-download"></span>';
    resultBoxStr += '   </a>';
    resultBoxStr += '   <div style="clear:both"></div>';
    resultBoxStr += '   <div class="query-result-box">';
    resultBoxStr += '       <div class="query-result-grid">';
    resultBoxStr += '           <div class="query-result-grid-table">';
    resultBoxStr += '               <table class="table sortable">';
    resultBoxStr += '                   <caption style="display: none;">' + uid + '</caption>';
    resultBoxStr += '                   <thead>';
    resultBoxStr += '                       <tr>';
    resultBoxStr += '                       </tr>';
    resultBoxStr += '                   </thead>';
    resultBoxStr += '                   <tbody>';
    resultBoxStr += '                   </tbody>';
    resultBoxStr += '               </table>';
    resultBoxStr += '           </div>';
    resultBoxStr += '           <div>';
    resultBoxStr += '               <button type="button" class="btn btn-default btn-xs btn-show-result-grid-more" uid="' + uid + '">More <span class="glyphicon glyphicon-plus" aria-hidden="true"></span></button>';
    resultBoxStr += '               <button type="button" class="btn btn-default btn-xs btn-download-result-excel" uid="' + uid + '">Download CSV <span class="fa fa-file-excel-o" aria-hidden="true"></span></button>';
    resultBoxStr += '           </div>';
    resultBoxStr += '       </div>';
    resultBoxStr += '       <div class="query-result-chart" id="chart-' + uid + '">';
    resultBoxStr += '           <div class="row chart-setting active">';
    resultBoxStr += '               <div class="col-xs-4 col-md-4">';
    resultBoxStr += '                   <div>Columns</div>';
    resultBoxStr += '                   <div class="chart-columns-select-area">';
    resultBoxStr += '                       <div class="chart-columns">';

    resultBoxStr += '                       </div>'
    resultBoxStr += '                   </div>';
    resultBoxStr += '               </div>';
    resultBoxStr += '               <div class="col-xs-4 col-md-4">';
    resultBoxStr += '                   <div>xAxis</div>';
    resultBoxStr += '                   <div class="chart-xaxis-select-area">';
    resultBoxStr += '                       <div class="chart-xaxis"></div>';
    resultBoxStr += '                   </div>';
    resultBoxStr += '               </div>';
    resultBoxStr += '               <div class="col-xs-4 col-md-4">';
    resultBoxStr += '                   <div>yAxis</div>';
    resultBoxStr += '                   <div class="chart-yaxis-select-area">';
    resultBoxStr += '                       <div class="chart-yaxis">';
    resultBoxStr += '                           <ul>';

    resultBoxStr += '                           </ul>';
    //resultBoxStr += '<div style="clear:both"></div>';

    resultBoxStr += '                       </div>';
    resultBoxStr += '                   </div>';
    resultBoxStr += '               </div>';
    resultBoxStr += '           </div>';
    resultBoxStr += '           <div>';
    resultBoxStr += '           <svg id="svg-' + uid + '"></svg>';
    resultBoxStr += '           </div>';
    resultBoxStr += '       </div>';
    resultBoxStr += '   </div>';
    resultBoxStr += '</div>';
    resultBoxStr += '';


    $('#result').prepend(resultBoxStr);
    $('.btn-run-query').addClass('disabled');

    $('.btn-close-result-box').each(function() {
        $(this).unbind('click');
        $(this).click(function() {
            var self = this;
            var progress = $(this).parents('.result-box').find('.progress').css('width');
            var progressBar = $(this).parents('.result-box').find('.progress-bar').css('width');
            if(progress == progressBar) {
                bootbox.confirm("Are you sure to remove task?", function(result) {
                    if(result) {
                        $(self).parents('.result-box').remove();
                    }
                });
            } else {
                bootbox.alert("You can remove task after status is Done or Killed");
            }
        });
    });

    $('.query-box').each(function() {
        $(this).unbind('keydown');
        $(this).keydown(function(e) {
            if (e.keyCode == 13 && e.shiftKey) {
                e.preventDefault();
                $(this).parents('.result-box').find('button.btn-run-query').trigger('click');
            }

        });

    });

    $('.btn-show-result-grid').each(function() {
        $(this).unbind('click');
        $(this).click(function() {
            $(this).parents('.result-box').find('button.btn-show-result').removeClass('active');
            $(this).parents('.result-box').find('button.btn-show-result-grid').addClass('active');

            $(this).parents('.result-box').find('div.query-result-grid').show();
            $(this).parents('.result-box').find('div.query-result-chart').hide();

            $(this).parents('.result-box').find('.btn-chart-setting-toggle').hide();
            $(this).parents('.result-box').find('.btn-download-chart-image').hide();
        });
    });
    $('.btn-show-result-chart-bar').each(function() {
        $(this).unbind('click');
        $(this).click(function() {
            $(this).parents('.result-box').find('button.btn-show-result').removeClass('active');
            $(this).parents('.result-box').find('button.btn-show-result-chart-bar').addClass('active');

            $(this).parents('.result-box').find('div.query-result-grid').hide();
            $(this).parents('.result-box').find('div.query-result-chart').show();

            $(this).parents('.result-box').find('.btn-chart-setting-toggle').show();
            $(this).parents('.result-box').find('.btn-download-chart-image').show();

            _chartType = 'bar';
            drawChart($(this).parents('.result-box').attr('id'), _chartType);
        });
    });
    $('.btn-show-result-chart-line').each(function() {
        $(this).unbind('click');
        $(this).click(function() {
            $(this).parents('.result-box').find('button.btn-show-result').removeClass('active');
            $(this).parents('.result-box').find('button.btn-show-result-chart-line').addClass('active');

            $(this).parents('.result-box').find('div.query-result-grid').hide();
            $(this).parents('.result-box').find('div.query-result-chart').show();

            $(this).parents('.result-box').find('.btn-chart-setting-toggle').show();
            $(this).parents('.result-box').find('.btn-download-chart-image').show();

            _chartType = 'line';
            drawChart($(this).parents('.result-box').attr('id'), _chartType);
        });
    });
    $('.btn-show-result-chart-area').each(function() {
        $(this).unbind('click');
        $(this).click(function() {
            $(this).parents('.result-box').find('button.btn-show-result').removeClass('active');
            $(this).parents('.result-box').find('button.btn-show-result-chart-area').addClass('active');

            $(this).parents('.result-box').find('div.query-result-grid').hide();
            $(this).parents('.result-box').find('div.query-result-chart').show();

            $(this).parents('.result-box').find('.btn-chart-setting-toggle').show();
            $(this).parents('.result-box').find('.btn-download-chart-image').show();

            _chartType = 'area';
            drawChart($(this).parents('.result-box').attr('id'), _chartType);
        });
    });
    $('.btn-show-result-chart-pie').each(function() {
        $(this).unbind('click');
        $(this).click(function() {
            $(this).parents('.result-box').find('button.btn-show-result').removeClass('active');
            $(this).parents('.result-box').find('button.btn-show-result-chart-pie').addClass('active');

            $(this).parents('.result-box').find('div.query-result-grid').hide();
            $(this).parents('.result-box').find('div.query-result-chart').show();

            $(this).parents('.result-box').find('.btn-chart-setting-toggle').show();
            $(this).parents('.result-box').find('.btn-download-chart-image').show();

            _chartType = 'pie';
            drawChart($(this).parents('.result-box').attr('id'), _chartType);
        });
    });
    $('.btn-show-result-chart-scatter').each(function() {
        $(this).unbind('click');
        $(this).click(function() {
            $(this).parents('.result-box').find('button.btn-show-result').removeClass('active');
            $(this).parents('.result-box').find('button.btn-show-result-chart-scatter').addClass('active');

            $(this).parents('.result-box').find('div.query-result-grid').hide();
            $(this).parents('.result-box').find('div.query-result-chart').show();

            $(this).parents('.result-box').find('.btn-chart-setting-toggle').show();
            $(this).parents('.result-box').find('.btn-download-chart-image').show();

            _chartType = 'scatter';
            drawChart($(this).parents('.result-box').attr('id'), _chartType);
        });
    });

    $('.btn-edit-query').each(function() {
        $(this).unbind('click');
        $(this).click(function() {
            $(this).parents('.result-box').find('textarea').addClass('active');
            $(this).parents('.result-box').find('textarea').attr("disabled", false).focus();
        });
    });

    $('.btn-show-result-grid-more').each(function () {
        $(this).unbind('click');
        $(this).click(function() {
            showQueryResultMore($(this).attr('uid'));
        })
    });

    $('.btn-download-result-excel').each(function() {
        $(this).unbind('click');
        $(this).click(function() {
            $(this).parents('.result-box').find('.query-result-grid-table table').tableToCSV();
        });
    });

    $('.btn-run-query[class~=task]').each(function() {
        $(this).unbind('click');
        $(this).click(function() {
            runQuery($(this).parents('.result-box').find('textarea.query').val(), false, $(this).parents('.result-box').attr('id'));
        });
    });

    $('.btn-kill-query[class~=task]').each(function() {
        $(this).unbind('click');
        $(this).click(function() {
            bootbox.confirm("Are you sure to kill task?", function(result) {
                if(result) {
                    //killQuery();
                    killQueryById(_uidToQueryId[uid]);
                }
            });



        });
    });

    $('#timeline .result-box .btn-chart-setting-toggle').each(function() {
        $(this).unbind('click');
        $(this).click(function() {
            var $setting = $(this).parents('.result-box').find('.chart-setting');
            if($setting.hasClass('active')) {
                $(this).find('span').removeClass('fa-caret-up');
                $(this).find('span').addClass('fa-caret-down');
                $setting.removeClass('active');
            } else {
                $(this).find('span').removeClass('fa-caret-down');
                $(this).find('span').addClass('fa-caret-up');
                $setting.addClass('active');
            }
        });
    });
    $('#timeline .result-box .btn-download-chart-image').each(function() {
        $(this).unbind('click');
        $(this).click(function() {
            var width = $('#svg-' + uid).width();
            var height = $('#svg-' + uid).height();
            console.log(width + ':' + height);
            var svg = '<svg width="' + width + '" height="' + height + '"><rect x="0" y="0" width="' + width + '" height="' + height + '" fill="white" fill-opacity="1" stroke-opacity="1" />' + $('#svg-' + uid).html() + '</svg>';
            //console.log(svg);
            var fileName = uid + '.png';
            canvg(document.getElementById('canvas'), svg);
            downloadCanvas(this, 'canvas', fileName);
        });
    });

    //$('.query-result-grid-table' ).resizable({
    //    handles: 's'
    //});

    $('[data-toggle="tooltip"]').tooltip();

    return uid;
}

function removeColumnButtonGroup(thiz, _uid) {
    var key = $(thiz).attr('key');

    delete _chartSettingYAxis[_uid][key];

    $(thiz).parents('li').remove();
    resizeSettingAxis(_uid);

    drawChart(_uid, _chartType);
    return false;
}
function removeColumnButton(thiz, _uid) {
    _chartSettingXAxis = [];

    $(thiz).parents('button').remove();

    drawChart(_uid, _chartType);
    return false;
}

function resetResultBox(uid) {

    $('.btn-run-query').addClass('disabled');
    $('#' + uid + ' .query-result-grid .query-result-grid-table table thead tr').html('');
    $('#' + uid + ' .query-result-grid .query-result-grid-table table tbody').html('');
    //$('#' + uid + ' .btn-show-result-grid').trigger('click');
    $('#' + uid + ' button.btn-show-result').removeClass('active');
    $('#' + uid + ' button.btn-show-result-grid').addClass('active');
    $('#' + uid + ' div.query-result-grid').show();
    $('#' + uid + ' div.query-result-chart').hide();
    $('#' + uid + ' .btn-chart-setting-toggle').hide();

    resetProgressbar(uid);
    resetStartTime(uid);
    resetRunTime(uid);
    return uid;
}

function showStartTime(id, unixtime) {
    var startTime = moment.unix(unixtime/1000).format('YYYY-MM-DD HH:mm:ss');
    $('#' + id + ' span.start-time').html(startTime)
}
function showRunTime(id, runTime) {
    $('#' + id + ' span.run-time').show();
    $('#' + id + ' span.run-time > span.time').html(runTime)
}
function resetStartTime(id) {
    $('#' + id + ' span.start-time').html('')
}
function resetRunTime(id) {
    $('#' + id + ' span.run-time').show();
    $('#' + id + ' span.run-time > span.time').html('')
}


function progressbar(id, percent, state) {
    $('#' + id + ' div.progress div.progress-bar').addClass('active');
    $('#' + id + ' div.progress div.progress-bar').css('width', percent + '%');
    $('#' + id + ' div.progress div.progress-bar').html(percent + '%');

    if(percent == 100) {
        $('#' + id + ' div.progress div.progress-bar').removeClass('active');
    }
    if(state == "KILLED" || state == "ERROR" || state == "FAILED") {
        $('#' + id + ' div.progress div.progress-bar').html(state);
    }
}
function resetProgressbar(id) {
    $('#' + id + ' div.progress div.progress-bar').removeClass('active');
    $('#' + id + ' div.progress div.progress-bar').css('width', 0 + '%');
    $('#' + id + ' div.progress div.progress-bar').html('0%');
}

function unixtimeTodatetime(unixtime) {
    var a = new Date(unixtime * 1000);
    var year = a.getFullYear();
    var month = a.getMonth();
    var date = a.getDate();
    var hour = a.getHours();
    var min = a.getMinutes();
    var sec = a.getSeconds();
    var datetime = year + '-' + month + '-' + date + '  ' + hour + ':' + min + ':' + sec ;
    return datetime;
}

function setDragDrop(uid) {
    $('#chart-' + uid + ' .allColumn').unbind('draggable');
    $('#chart-' + uid + ' .allColumn').draggable({
        cancel: false,
        //revert: true,
        appendTo: "body",
        helper: "clone"
    });

    $('#chart-' + uid + ' .chart-xaxis').unbind('droppable');
    $('#chart-' + uid + ' .chart-xaxis').droppable({
        drop: function (event, ui) {
            var bStr = "";
            bStr += '   <button type="button" class="btn btn-success btn-xs" aria-haspopup="true" aria-expanded="false">';
            bStr += '       ' + ui.draggable.text() + ' <span class="glyphicon glyphicon-remove" onClick=\'removeColumnButton(this, "' + uid + '")\'></span>';
            bStr += '   </button>';
            $(this).html('');
            $(this).append(bStr);
            _chartSettingXAxis[uid] = ui.draggable.text();

            drawChart(uid, _chartType);
        }
    });

    $('#chart-' + uid + ' .chart-yaxis ul').unbind('droppable');
    $('#chart-' + uid + ' .chart-yaxis ul').droppable({
        drop: function (event, ui) {

            var yAxisCheck = _chartSettingYAxis[uid];
            for (k in yAxisCheck) {
                if(k == ui.draggable.text()) {
                    return false;
                }
            }

            var bStr = "";
            bStr += '<li class="">';
            bStr += '<div class="btn-group">';
            bStr += '   <button type="button" class="btn btn-info btn-xs dropdown-toggle" data-toggle="dropdown" aria-haspopup="true" aria-expanded="false">';
            bStr += '       <span class="text">' + ui.draggable.text() + '</span> <span class="dataType">Sum</span> <span class="glyphicon glyphicon-remove btn-remove" key="' + ui.draggable.text() + '" onClick=\'removeColumnButtonGroup(this, "' + uid + '")\'></span>';
            bStr += '   </button>';
            bStr += '   <ul class="dropdown-menu">';
            bStr += '       <li><a href="#none">sum</a></li>';
            bStr += '       <li><a href="#none">count</a></li>';
            bStr += '       <li><a href="#none">avg</a></li>';
            bStr += '       <li><a href="#none">min</a></li>';
            bStr += '       <li><a href="#none">max</a></li>';
            bStr += '   </ul>';
            bStr += '</div>';
            bStr += '</li>';

            $(this).append(bStr);
            var _yAxis= [];
            _yAxis[ui.draggable.text()] = 'sum';
            if(typeof _chartSettingYAxis[uid] == 'undefined' || typeof _chartSettingYAxis[uid] == null || typeof _chartSettingYAxis[uid] == '') {
                _chartSettingYAxis[uid] = [];
            }

            _chartSettingYAxis[uid][ui.draggable.text()] = 'sum';

            resizeSettingAxis(uid);

            drawChart(uid, _chartType);

            $('#chart-' + uid + ' .chart-yaxis ul.dropdown-menu > li > a').each(function() {
                $(this).unbind('click');
                $(this).click(function() {
                    //$(this).parents('.btn-group').find('button span.text').html($(this).text());
                    $(this).parents('.btn-group').find('button span.dataType').html(toTitleCase($(this).text()));
                    $(this).parents('.btn-group').find('button span.btn-remove').attr('key', $(this).text());
                    _chartSettingYAxis[uid][$(this).parents('.btn-group').find('button span.text').text()] = $(this).text();
                    drawChart(uid, _chartType);
                })
            });
        }
    });
}
function resizeSettingAxis(_uid) {
    var $obj = $('#' + _uid + ' .query-result-box .query-result-chart .chart-yaxis-select-area .chart-yaxis > ul')
    var $target = $('#' + _uid + ' .query-result-box .query-result-chart .chart-yaxis-select-area .chart-yaxis')
    var height = $obj.height();

    if(height > 127) {
        $target.height(height - 7 + 5);
    } else {
        $target.height(height - 7);
    }
}
function drawChart(_uid, _chartType) {
    var data = getTransData(_uid, _chartSettingXAxis[_uid], _chartType);

    /**
     * chart tooltip bug 수정
     */
    d3.select('#chart-' + _uid + ' svg > *').remove();

    d3.select('#chart-' + _uid + ' svg > *')
        .on("mousemove", null)
        .on("mouseout", null)
        .on("dblclick", null)
        .on("click", null);
    d3.select('#chart-' + _uid + ' svg')
        .on("mousemove", null)
        .on("mouseout", null)
        .on("dblclick", null)
        .on("click", null);
    $('#chart-' + _uid + ' div.nvtooltip').remove();
    /**
     * /chart tooltip bug 수정
     */

    //nv.addGraph(function () {
    //    var chart = null;
        switch (_chartType) {
            case 'bar':
                _chart[_uid] = nv.models.multiBarChart();
                break;
            case 'line':
                _chart[_uid] = nv.models.lineChart();
                _chart[_uid].useInteractiveGuideline(true);    //Tooltips which show all data points. Very nice!
                break;
            case 'area':
                _chart[_uid] = nv.models.stackedAreaChart();
                _chart[_uid].useInteractiveGuideline(true);    //Tooltips which show all data points. Very nice!
                break;
            case 'pie':
                _chart[_uid] = nv.models.pieChart()
                    .x(function(d) { return d.label })
                    .y(function(d) { return d.value });
                break;
            case 'scatter':
                _chart[_uid] = nv.models.scatterChart()
                    .showDistX(true)    //showDist, when true, will display those little distribution lines on the axis.
                    .showDistY(true);

                break;
            default:
                _chart[_uid] = nv.models.multiBarChart();
                break;
        }

        //chart.transition_duration(350);
        //_chartData[_uid] = _data;
        //data = sinAndCos();
        if(_chartType != 'pie') {
            _chart[_uid].margin({
                left: 100,
                right: 50
            });  //Adjust chart margins to give the x-axis some breathing room.
            _chart[_uid].yAxis.tickFormat(d3.format(',.2f'));
        }

        _vis[_uid] = d3.select('#chart-' + _uid + ' svg')    //Select the <svg> element you want to render the chart in.
            .datum(data)         //Populate the <svg> element with chart data...
            .call(_chart[_uid]);

        nv.utils.windowResize(function() {
            _chart[_uid].update();
        });
        //return chart;
    //});

}
function getTransData(_uid, key, _chartType) {
    var num = null;
    var result = [];

    for(var i = 0; i < _chartData[_uid].metadata.columnMetadata.length; i++) {
        if(key == _chartData[_uid].metadata.columnMetadata[i].columnName) {
            num = i;
            break;
        }
    }

    var yAxis = _chartSettingYAxis[_uid]
    for (k in yAxis) {
        var x = [];
        var y = [];
        var item = {};
        item['key'] = k;
        item['values'] = [];
        var data = [];

        var dataNum = null;

        for(var i = 0; i < _chartData[_uid].metadata.columnMetadata.length; i++) {
            if(k == _chartData[_uid].metadata.columnMetadata[i].columnName) {
                dataNum = i;
                break;
            }
        }
        for(var i = 0; i < _chartData[_uid].data.length; i++) {
            x.push(_chartData[_uid].data[i][num]);
            y.push(_chartData[_uid].data[i][dataNum]);
            //data.push({"x": _chartData[_uid].data[i][num], "y": Number(_chartData[_uid].data[i][dataNum])});

        }
        //dataSum(x, y);
        if(yAxis[k] == 'sum') {
            item['values'] = dataSum(x, y);
        } else if(yAxis[k] == 'count') {
            item['values'] = dataCount(x, y);
        } else if(yAxis[k] == 'avg') {
            item['values'] = dataAvg(x, y);
        } else if(yAxis[k] == 'min') {
            item['values'] = dataMin(x, y);
        } else if(yAxis[k] == 'max') {
            item['values'] = dataMax(x, y);
        }

        if(_chartType == 'pie') {
            var item = item['values'];
            for(var i = 0; i < item.length; i++) {
                result.push({'label': item[i].x, 'value': item[i].y});
            }
            return result;
        }

        result.push(item);
    }

    return result;
}

function dataSum(x, y) {
    var result = [];
    var sum = [];

    for(var i = 0; i < x.length; i++) {
        if(sum[x[i]]) {
            sum[x[i]] = sum[x[i]] + Number(y[i]);
        } else {
            sum[x[i]] = Number(y[i]);
        }
    }
    for (k in sum) {
        result.push({x: k, y: sum[k]});
    }
    return result;
}

function dataCount(x, y) {
    var result = [];
    var count = [];

    for(var i = 0; i < x.length; i++) {
        if(count[x[i]]) {
            count[x[i]]++;
        } else {
            count[x[i]] = 1;
        }
    }
    for (k in count) {
        result.push({x: k, y: count[k]});
    }
    return result;
}

function dataAvg(x, y) {
    var result = [];
    var sum = [];
    var count = [];

    for(var i = 0; i < x.length; i++) {
        if(sum[x[i]]) {
            //sum[x[i]] += Number(y[i]);
            sum[x[i]] = sum[x[i]] + Number(y[i]);
            count[x[i]]++;
        } else {
            sum[x[i]] = Number(y[i]);
            count[x[i]] = 1;
        }
    }

    for (k in sum) {
        var avg = sum[k] / count[k];
        result.push({x: k, y: avg.toFixed(2)});
    }
    return result;
}
function dataMin(x, y) {
    var result = [];
    var min = [];

    for(var i = 0; i < x.length; i++) {
        if(min[x[i]]) {
            if(min[x[i]] > Number(y[i])) {
                min[x[i]] = Number(y[i]);
            }
        } else {
            min[x[i]] = Number(y[i]);
        }
    }
    for (k in min) {
        result.push({x: k, y: min[k]});
    }
    return result;
}
function dataMax(x, y) {
    var result = [];
    var max = [];

    for(var i = 0; i < x.length; i++) {
        if(max[x[i]]) {
            if(max[x[i]] < Number(y[i])) {
                max[x[i]] = Number(y[i]);
            }
        } else {
            max[x[i]] = Number(y[i]);
        }
    }
    for (k in max) {
        result.push({x: k, y: max[k]});
    }
    return result;
}



function toTitleCase(str)
{
    return str.replace(/\w\S*/g, function(txt){return txt.charAt(0).toUpperCase() + txt.substr(1).toLowerCase();});
}

var T=Number.MAX_VALUE;

function downloadCanvas(link, canvasId, filename) {
    link.href = document.getElementById(canvasId).toDataURL();
    link.target = '_blank';
    link.download = filename;
}