#!/bin/sh
#
# 听书 · CGI 公共函数
#
# 所有 api/* 脚本共用这里的响应头和参数处理，避免各写一份、改一处漏七处。
# 依赖 busybox ash（OpenWrt 默认 /bin/sh），只用 POSIX 语法 + local。
#
#   ts_header                 输出 JSON 响应头（不含状态行）
#   ts_fail <code> <msg>      输出 {"ok":false,...} 并退出
#   ts_ok [extra]             输出 {"ok":true<extra>} 并退出
#   ts_param <qs> <key>       从 a=1&b=2 里取值
#   ts_urldecode <str>        %E4%B8%89 → 三
#   ts_json_escape <str>      转义成 JSON 字符串内容
#   ts_read_body [max]        读取请求体到 $TS_BODY，超长/非法返回 1
#   ts_write_atomic <file>    从 stdin 原子写入（先写临时文件再 mv）
#   ts_is_uint <str>          纯数字校验
#   ts_is_hex <str>           小写十六进制校验
#

# ---- 输出 ----

ts_header() {
	printf 'Content-Type: application/json; charset=utf-8\r\n'
	printf 'Cache-Control: no-store\r\n'
	printf 'X-Content-Type-Options: nosniff\r\n'
	printf '\r\n'
}

ts_status_line() {
	case "$1" in
		400) printf 'Status: 400 Bad Request\r\n' ;;
		401) printf 'Status: 401 Unauthorized\r\n' ;;
		403) printf 'Status: 403 Forbidden\r\n' ;;
		404) printf 'Status: 404 Not Found\r\n' ;;
		413) printf 'Status: 413 Payload Too Large\r\n' ;;
		500) printf 'Status: 500 Internal Server Error\r\n' ;;
		*) printf 'Status: %s Error\r\n' "$1" ;;
	esac
}

# ts_fail <状态码> <说明>
ts_fail() {
	local code msg
	code="$1"
	msg="$(ts_json_escape "$2")"
	[ "$code" -ge 400 ] && ts_status_line "$code"
	ts_header
	printf '{"ok":false,"error":"%s"}\n' "$msg"
	exit 0
}

# ts_ok [",key":value] —— 追加字段由调用方拼好，必须自带前导逗号
ts_ok() {
	ts_header
	printf '{"ok":true%s}\n' "$1"
	exit 0
}

# ---- 参数 ----

ts_json_escape() {
	printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr -d '\r\n\t'
}

ts_param() {
	printf '%s' "$1" | tr '&' '\n' | sed -n "s/^$2=//p" | head -n 1
}

ts_urldecode() {
	printf '%b' "$(printf '%s' "$1" | sed 's/%/\\x/g')"
}

ts_is_uint() {
	case "$1" in ''|*[!0-9]*) return 1 ;; esac
	return 0
}

ts_is_hex() {
	case "$1" in ''|*[!0-9a-f]*) return 1 ;; esac
	return 0
}

# ---- 请求体 ----

# 读取请求体到 TS_BODY。$1 = 允许的最大字节数（省略则不限制）
ts_read_body() {
	local len max
	max="${1:-0}"
	len="${CONTENT_LENGTH:-0}"
	ts_is_uint "$len" || len=0
	if [ "$max" -gt 0 ] && [ "$len" -gt "$max" ]; then
		return 1
	fi
	TS_BODY=""
	[ "$len" -gt 0 ] && TS_BODY="$(head -c "$len" 2>/dev/null)"
	return 0
}

# ---- 落盘 ----

# 从 stdin 原子写入 $1：先写同目录临时文件，再 mv 覆盖
ts_write_atomic() {
	local target tmp
	target="$1"
	tmp="$target.$$"
	cat > "$tmp" 2>/dev/null || {
		rm -f "$tmp" 2>/dev/null
		return 1
	}
	mv -f "$tmp" "$target" 2>/dev/null || {
		rm -f "$tmp" 2>/dev/null
		return 1
	}
	return 0
}
