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
/*
 * IE7 이하 버전에서 "'console'이(가) 정의되지 않았습니다." 에러 처리
 */
var console = console || {
    log:function(){},
    warn:function(){},
    error:function(){}
};

$(document).ready(function() {
	/**
	 * gnb 마우스 오버시 변경
	 */
	$('.gnb ul.menu li').not('#gnb_logout').hover(function() {
		$(this).addClass('over');
	}, function() {
		if(!$(this).hasClass('active')) {
			$(this).removeClass('over');
		}
	});
	/**
	 * 이미지 활성화 유지
	 */
	$("img.active").each(function() {
		var $imgActive = $(this).attr('src');
		$(this).attr('src', $imgActive.replace("_off", "_on"));	
	});
	
	/**
	 * 이미지 롤오버 기능
	 */
	$("img.rollover").hover(function() {
		this.src = this.src.replace("_off", "_on");
	}, function() {
		if(!$(this).hasClass('active')) {
			this.src = this.src.replace("_on", "_off");
		}
	});

	/**
	 * blackUI 높이 조정
	 */
	$('.black').height($('body').height());
	
	/**
	 * location 서브메뉴 기능
	 */
	/*
	$('#location .region .set_region').bind('mouseover', function() {
		$('#location .region .set_region .list').show();
		$(this).mouseout(function() {
			$('#location .region .set_region .list').hide();
		});
	});
	*/
	/*
	$('div.button-group a.btn img.btn_img').each(function() {
		$(this).click(function() {
			console.log('aaa');
			
		})
	});
	*/
});
/**
 * Progress Bar
 * @param $
 */
(function( $ ) {
	jQuery.fn.progressBar = function (percent) {
		return this.each( function(){
			if(typeof percent != 'number') percent = 0;
			if(percent < 0 || 100 < percent) percent = 0;
			
			$(this).find('.progress_bar_off .progress_bar_on').css('width', percent+'%');
		});
	};
})( jQuery );


/**
 * 흔들리는 이벤트
 * @param $
 */
(function( $ ) {
	jQuery.fn.wave = function (sw) {
		return this.each( function(){
			if(sw  == 's') {				// S파 - 횡으로 흔들림
				for(var i = 10; i > 1; i = Math.ceil(i / 2)) {
					$(this).animate({
						marginLeft: '+='+ i +'px'
					}, 100);
					$(this).animate({
						marginLeft: '-='+ i +'px'
					}, 100);
				}
			} else if(sw  == 'p') {			// P파 - 종으로 움직임
				for(var i = 10; i > 1; i = Math.ceil(i / 2)) {
					$(this).animate({
						marginTop: '+='+ i +'px'
					}, 100);
					$(this).animate({
						marginTop: '-='+ i +'px'
					}, 100);
				}
			}
			
		});
	};
})( jQuery );

/**
 * POST 이동
 * post_goto('경로', {'parm1':'val1','parm2':'val2'});
 */ 
function post_goto(url, parm, target) {
	var f = document.createElement('form');

	var objs, value;
	for ( var key in parm) {
		value = parm[key];
		objs = document.createElement('input');
		objs.setAttribute('type', 'hidden');
		objs.setAttribute('name', key);
		objs.setAttribute('value', value);
		f.appendChild(objs);
	}

	if (target)
		f.setAttribute('target', target);

	f.setAttribute('method', 'post');
	f.setAttribute('action', url);
	document.body.appendChild(f);
	f.submit();
}

/**
 * POST 창
 * post_win(url, name, {'parm1':'val1','parm2':'val2'}, '옵션');
 */
function post_win(url, name, parm, opt) {
	var temp_win = window.open('', name, opt);
	post_goto(url, parm, name);
}


/**
 * Byte 단위 숫자를 단위별로 계산해서 리턴
 * @param bytes
 * @returns {String}
 */
function flowBytesToSize(bytes) {
	var sizes = [ 'Bytes', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB', 'VB', 'RB', 'OB', 'QB', 'XC' ];
	if (bytes == 0 || typeof bytes != 'number')
		return '0 Bytes';
	var i = parseInt(Math.floor(Math.log(bytes) / Math.log(1024)));
	return Math.round(bytes / Math.pow(1024, i), 2) + ' ' + sizes[i];
};

/**
 * utime 을 시간 문자열로 변환
 * @param utime
 * @returns {String}
 */
function utimeToString(utime) {
	var times = [ 60, 60, 60, 24 ]
	var unit = [ 'seconds', 'minutes', 'hours', 'day' ];
	if (utime == 0 || typeof utime != 'number')
		return '0minutes';
	//var i = parseInt(Math.floor(Math.log(bytes) / Math.log(1024)));
	//return Math.round(bytes / Math.pow(1024, i), 2) + ' ' + sizes[i];
	
	var result = "";
	for(var i = 0; i < 4; i++) {
		var r = Math.floor(utime % times[i]);
		if(r > 0) {
			if(i > 0) {
				result = ' ' + result;
			}
			result = r + unit[i] + result 
		}
		utime = Math.floor(utime / times[i]);
	}
	return result;
	
};

/**
 * Numeric only control handler
 * example $("#yourTextBoxName").ForceNumericOnly();
 */
jQuery.fn.ForceNumericOnly =
function()
{
    return this.each(function()
    {
        $(this).keydown(function(e)
        {
            var key = e.charCode || e.keyCode || 0;
            // allow backspace, tab, delete, enter, arrows, numbers and keypad numbers ONLY
            // home, end, period, and numpad decimal
            return (
                key == 8 || 
                key == 9 ||
                key == 13 ||
                key == 46 ||
                key == 110 ||
                key == 190 ||
                (key >= 35 && key <= 40) ||
                (key >= 48 && key <= 57) ||
                (key >= 96 && key <= 105));
        });
    });
};