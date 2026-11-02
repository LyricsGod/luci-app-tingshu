#!/bin/sh
#
# 听书 · 用户 / 会话公共函数
#
# 用户目录结构（overlay，重启不丢）：
#   /etc/tingshu/users/<用户名>/account       role=admin|user / salt / hash / created
#   /etc/tingshu/users/<用户名>/progress/     每本书一个 json
#   /etc/tingshu/users/<用户名>/collections.json
#   /etc/tingshu/users/<用户名>/pausemarks.json
#   /etc/tingshu/users/<用户名>/layout.json
#
# 会话放 /tmp，重启后重新登录即可：
#   /tmp/tingshu-sessions/<token>  用户名 / 角色 / 过期时间
#
# 密码用 openssl passwd -6（SHA-512 crypt）加盐保存，不存明文。
#

TS_USERS_DIR="${TINGSHU_USERS_DIR:-/etc/tingshu/users}"
TS_SESSION_DIR="${TINGSHU_SESSIONS_DIR:-/tmp/tingshu-sessions}"
TS_SESSION_TTL="${TINGSHU_SESSION_TTL:-2592000}"   # 30 天
TS_MIN_PASSWORD=6

mkdir -p "$TS_USERS_DIR" "$TS_SESSION_DIR" 2>/dev/null
chmod 700 "$TS_USERS_DIR" "$TS_SESSION_DIR" 2>/dev/null

ts_urldecode() {
	printf '%b' "$(printf '%s' "$1" | sed 's/%/\\x/g; s/+/ /g')"
}

ts_param() {
	printf '%s' "$1" | tr '&' '\n' | sed -n "s/^$2=//p" | head -n 1
}

ts_safe_name() {
	case "$1" in
		''|*[!A-Za-z0-9_-]*) return 1 ;;
	esac
	[ ${#1} -ge 1 ] && [ ${#1} -le 32 ] || return 1
	return 0
}

ts_user_home() {
	printf '%s/%s' "$TS_USERS_DIR" "$1"
}

ts_user_exists() {
	[ -f "$TS_USERS_DIR/$1/account" ]
}

ts_user_role() {
	sed -n 's/^role=//p' "$TS_USERS_DIR/$1/account" 2>/dev/null | head -n 1
}

ts_user_count() {
	ls -1d "$TS_USERS_DIR"/*/account 2>/dev/null | wc -l | tr -d ' '
}

ts_count_admins() {
	n=0
	for f in "$TS_USERS_DIR"/*/account; do
		[ -f "$f" ] || continue
		[ "$(sed -n 's/^role=//p' "$f" | head -n 1)" = "admin" ] && n=$((n + 1))
	done
	printf '%s' "$n"
}

ts_new_salt() {
	hexdump -n 8 -e '8/1 "%02x"' /dev/urandom
}

ts_new_token() {
	hexdump -n 32 -e '32/1 "%02x"' /dev/urandom
}

ts_hash_password() {
	openssl passwd -6 -salt "$1" "$2" 2>/dev/null
}

ts_check_password() {
	_u="$1"
	_pw="$2"
	[ -f "$TS_USERS_DIR/$_u/account" ] || return 1
	_salt="$(sed -n 's/^salt=//p' "$TS_USERS_DIR/$_u/account" | head -n 1)"
	_stored="$(sed -n 's/^hash=//p' "$TS_USERS_DIR/$_u/account" | head -n 1)"
	[ -n "$_salt" ] && [ -n "$_stored" ] || return 1
	_actual="$(ts_hash_password "$_salt" "$_pw")"
	[ -n "$_actual" ] && [ "$_actual" = "$_stored" ] || return 1
	return 0
}

ts_create_user() {
	_u="$1"
	_pw="$2"
	_role="$3"
	ts_safe_name "$_u" || return 2
	ts_user_exists "$_u" && return 3
	[ ${#_pw} -ge "$TS_MIN_PASSWORD" ] && [ ${#_pw} -le 128 ] || return 4
	case "$_pw" in *[![:print:]]*) return 4 ;; esac
	case "$_role" in admin|user) ;; *) return 5 ;; esac

	_home="$TS_USERS_DIR/$_u"
	mkdir -p "$_home/progress" 2>/dev/null || return 6
	_salt="$(ts_new_salt)"
	_hash="$(ts_hash_password "$_salt" "$_pw")"
	[ -n "$_hash" ] || return 6
	_tmp="$_home/.account.$$"
	{
		printf 'role=%s\n' "$_role"
		printf 'salt=%s\n' "$_salt"
		printf 'hash=%s\n' "$_hash"
		printf 'created=%s\n' "$(date +%s)"
	} > "$_tmp" || { rm -f "$_tmp"; return 6; }
	mv -f "$_tmp" "$_home/account" || { rm -f "$_tmp"; return 6; }
	chmod 600 "$_home/account" 2>/dev/null
	chmod 700 "$_home" 2>/dev/null
	return 0
}

ts_set_password() {
	_u="$1"
	_pw="$2"
	ts_user_exists "$_u" || return 3
	[ ${#_pw} -ge "$TS_MIN_PASSWORD" ] && [ ${#_pw} -le 128 ] || return 4
	case "$_pw" in *[![:print:]]*) return 4 ;; esac
	_salt="$(ts_new_salt)"
	_hash="$(ts_hash_password "$_salt" "$_pw")"
	[ -n "$_hash" ] || return 6
	_account="$TS_USERS_DIR/$_u/account"
	_tmp="$TS_USERS_DIR/$_u/.account.$$"
	{
		printf 'role=%s\n' "$(ts_user_role "$_u")"
		printf 'salt=%s\n' "$_salt"
		printf 'hash=%s\n' "$_hash"
		printf 'created=%s\n' "$(sed -n 's/^created=//p' "$_account" | head -n 1)"
	} > "$_tmp" || { rm -f "$_tmp"; return 6; }
	mv -f "$_tmp" "$_account" || { rm -f "$_tmp"; return 6; }
	chmod 600 "$_account" 2>/dev/null
	return 0
}

ts_delete_user_data() {
	_u="$1"
	ts_safe_name "$_u" || return 2
	rm -rf "$TS_USERS_DIR/$_u"
	ts_delete_sessions_for "$_u"
}

ts_delete_sessions_for() {
	_u="$1"
	for _sf in "$TS_SESSION_DIR"/*; do
		[ -f "$_sf" ] || continue
		[ "$(sed -n '1p' "$_sf")" = "$_u" ] && rm -f "$_sf"
	done
}

ts_create_session() {
	_u="$1"
	_role="$2"
	_token="$(ts_new_token)"
	[ ${#_token} -eq 64 ] || return 1
	_exp=$(( $(date +%s) + TS_SESSION_TTL ))
	printf '%s\n%s\n%s\n' "$_u" "$_role" "$_exp" > "$TS_SESSION_DIR/$_token" || return 1
	chmod 600 "$TS_SESSION_DIR/$_token" 2>/dev/null
	TS_TOKEN="$_token"
	printf '%s' "$_token"
}

ts_token_from_request() {
	_token=""
	if [ -n "${HTTP_X_AUTH_TOKEN:-}" ]; then
		_token="$HTTP_X_AUTH_TOKEN"
	elif [ -n "${HTTP_AUTHORIZATION:-}" ]; then
		_token="$(printf '%s' "$HTTP_AUTHORIZATION" | sed -n 's/^[Bb]earer[[:space:]][[:space:]]*//p')"
	fi
	if [ -z "$_token" ] && [ -n "${HTTP_COOKIE:-}" ]; then
		_token="$(printf '%s' "$HTTP_COOKIE" | tr ';' '\n' \
			| sed -n 's/^[[:space:]]*tingshu_session=\([0-9a-fA-F]*\).*/\1/p' | head -n 1)"
	fi
	printf '%s' "$_token"
}

ts_load_session() {
	_token="$(ts_token_from_request)"
	case "$_token" in
		''|*[!0-9a-fA-F]*) return 1 ;;
	esac
	[ ${#_token} -eq 64 ] || return 1
	_sf="$TS_SESSION_DIR/$_token"
	[ -f "$_sf" ] || return 1
	_u="$(sed -n '1p' "$_sf")"
	_role="$(sed -n '2p' "$_sf")"
	_exp="$(sed -n '3p' "$_sf")"
	case "$_exp" in ''|*[!0-9]*) rm -f "$_sf"; return 1 ;; esac
	[ "$_exp" -gt "$(date +%s)" ] || { rm -f "$_sf"; return 1; }
	ts_user_exists "$_u" || { rm -f "$_sf"; return 1; }
	# 角色以账号文件为准
	_role="$(ts_user_role "$_u")"
	TS_USER="$_u"
	TS_ROLE="$_role"
	TS_TOKEN="$_token"
	return 0
}

ts_send_401() {
	printf 'Status: 401 Unauthorized\r\n'
	printf 'Content-Type: application/json; charset=utf-8\r\n'
	printf 'Cache-Control: no-store\r\n'
	printf 'X-Content-Type-Options: nosniff\r\n'
	printf '\r\n'
}

ts_send_403() {
	printf 'Status: 403 Forbidden\r\n'
	printf 'Content-Type: application/json; charset=utf-8\r\n'
	printf 'Cache-Control: no-store\r\n'
	printf 'X-Content-Type-Options: nosniff\r\n'
	printf '\r\n'
}

ts_require_auth() {
	if [ "${TINGSHU_AUTH_DISABLED:-0}" = "1" ]; then
		TS_USER="admin"
		TS_ROLE="admin"
		return 0
	fi
	ts_load_session && return 0
	ts_send_401
	printf '{"ok":false,"error":"unauthorized"}\n'
	exit 0
}

ts_require_admin() {
	ts_require_auth
	[ "$TS_ROLE" = "admin" ] && return 0
	ts_send_403
	printf '{"ok":false,"error":"forbidden"}\n'
	exit 0
}

ts_set_cookie() {
	printf 'Set-Cookie: tingshu_session=%s; Path=/; HttpOnly; SameSite=Lax; Max-Age=%s\r\n' "$1" "$TS_SESSION_TTL"
}

ts_clear_cookie() {
	printf 'Set-Cookie: tingshu_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0\r\n'
}

# 首次创建管理员时，把旧的单用户数据迁到这个账号下
ts_migrate_legacy() {
	_u="$1"
	_home="$TS_USERS_DIR/$_u"
	[ -d "$_home" ] || return 1
	mkdir -p "$_home/progress" 2>/dev/null
	if [ -d /etc/tingshu/progress ] && [ -z "$(ls -A "$_home/progress" 2>/dev/null)" ]; then
		cp -a /etc/tingshu/progress/. "$_home/progress/" 2>/dev/null
	fi
	[ -f /etc/tingshu/collections.json ] && [ ! -f "$_home/collections.json" ] \
		&& cp -f /etc/tingshu/collections.json "$_home/collections.json" 2>/dev/null
	[ -f /etc/tingshu/pausemarks.json ] && [ ! -f "$_home/pausemarks.json" ] \
		&& cp -f /etc/tingshu/pausemarks.json "$_home/pausemarks.json" 2>/dev/null
	[ -f /etc/tingshu/layout.json ] && [ ! -f "$_home/layout.json" ] \
		&& cp -f /etc/tingshu/layout.json "$_home/layout.json" 2>/dev/null
	chmod 700 "$_home" "$_home/progress" 2>/dev/null
	return 0
}
