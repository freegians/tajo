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
try {
    $(function() {
        console.log("    ________                                                          ");
        console.log("   /  ____  \\                           _                             ");
        console.log("  |  /    \\__|   _  __   _      _    __| |__    ____    _  __         ");
        console.log("  |  |          | |/ _| | |    | |  |___ ___| /  __  \\ | |/ _|        ");
        console.log("  |  |   __ __  | / /   | |    | |     | |    | |__| | | / /          ");
        console.log("  |  |  |__   | |  /    | |    | |     | |    | ____/  |  /           ");
        console.log("  |  \\_____|  | | |     |  \\___/  \\    | |_   | |____  | |          ");
        console.log("   \\_________/  |_|      \\______/\\_|   \\___|   \\_____| |_|         ");
        console.log("\n      Empowering Your Data.\n");
        console.log("\x3e \x3e \x3e http://www.gruter.com \n");
        console.log("\x3e \x3e \x3e https://www.facebook.com/GruterCorp \n");

    });
} catch (e) {}

$(function () {
    $('[data-toggle="tooltip"]').tooltip();     // tooltip 활성

});

$(document).ready(function() {
    textarea_resize(document.getElementById('txt-query'));


    /* 오른쪽 메뉴 활성 비활성 */
    $('#switch').on('click', function () {
        $('#timeline').toggleClass('col-md-9 col-md-5');
        $('#menu-right').toggleClass('col-md-3 col-md-7');
    });

    $('.tit-workbook-item').each(function() {
        $(this).click(function() {
            $('#timeline').toggleClass('col-md-9 col-md-5');
            $('#menu-right').toggleClass('col-md-3 col-md-7');

            $('.query-box .text-box').toggleClass('col-md-9 col-md-12');
            $('.query-box .button-box').toggleClass('col-md-3 col-md-12');

            if($('.workbook-item-page-list').hasClass('active')) {
                $('.workbook-item-page-list').removeClass('active');
            } else {
                $(this).parents('.workbook-item').find('.workbook-item-page-list').addClass('active');
            }
        });
    });
    /* /오른쪽 메뉴 활성 비활성 */


    //$('#tit-workbook-area').click(function() {
    //    $(this).addClass('active');
    //    $('#tit-workbook').prop('readonly', false);
    //    $('#tit-workbook').focus();
    //
    //    $('#tit-workbook').focusout(function() {
    //        $('#tit-workbook-area').removeClass('active');
    //        $('#tit-workbook').prop('readonly', true);
    //    });
    //
    //});
    //$('#desc-workbook-area').click(function() {
    //    $(this).addClass('active');
    //    $('#desc-workbook').prop('readonly', false);
    //    $('#desc-workbook').focus();
    //
    //    $('#desc-workbook').focusout(function() {
    //        $('#desc-workbook-area').removeClass('active');
    //        $('#desc-workbook').prop('readonly', true);
    //    });
    //
    //});
});

function guid() {
    function s4() {
        return Math.floor((1 + Math.random()) * 0x10000)
            .toString(16)
            .substring(1);
    }
//            return s4() + s4() + '-' + s4() + '-' + s4() + '-' +
//                    s4() + '-' + s4() + s4() + s4();
    return s4() + s4() + s4();
}

function textarea_resize(obj) {
    obj.style.height = "1px";
    obj.style.height = (20+obj.scrollHeight)+"px";
}