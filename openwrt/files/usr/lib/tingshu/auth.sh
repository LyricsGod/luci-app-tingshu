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
# 约定：对外暴露的只有 TS_USER / TS_ROLE / TS_TOKEN 三个全局变量，
# 其余中间变量一律 local，避免把调用方的变量冲掉。
#

. "${TINGSHU_LIB_DIR:-/usr/lib/tingshu}/http.sh"

TS_USERS_DIR="${TINGSHU_USERS_DIR:-/etc/tingshu/users}"
TS_SESSION_DIR="${TINGSHU_SESSIONS_DIR:-/tmp/tingshu-sessions}"
TS_SESSION_TTL="${TINGSHU_SESSION_TTL:-2592000}"   # 30 天
TS_MIN_PASSWORD=6

mkdir -p "$TS_USERS_DIR" "$TS_SESSION_DIR" 2>/dev/null
chmod 700 "$TS_USERS_DIR" "$TS_SESSION_DIR" 2>/dev/null

# ---- 用户信息 ----

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
	local n f
	n=0
	for f in "$TS_USERS_DIR"/*/account; do
		[ -f "$f" ] || continue
		[ "$(sed -n 's/^role=//p' "$f" | head -n 1)" = "admin" ] && n=$((n + 1))
	done
	printf '%s' "$n"
}

ts_safe_name() {
	case "$1" in
		''|*[!A-Za-z0-9_-]*) return 1 ;;
	esac
	[ ${#1} -ge 1 ] && [ ${#1} -le 32 ] || return 1
	return 0
}

# ---- 密码 ----

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
	local user pw salt stored actual
	user="$1"
	pw="$2"
	[ -f "$TS_USERS_DIR/$user/account" ] || return 1
	salt="$(sed -n 's/^salt=//p' "$TS_USERS_DIR/$user/account" | head -n 1)"
	stored="$(sed -n 's/^hash=//p' "$TS_USERS_DIR/$user/account" | head -n 1)"
	[ -n "$salt" ] && [ -n "$stored" ] || return 1
	actual="$(ts_hash_password "$salt" "$pw")"
	[ -n "$actual" ] && [ "$actual" = "$stored" ] || return 1
	return 0
}

# ts_create_user <user> <password> <role>
# 返回：0 成功 / 2 用户名非法 / 3 已存在 / 4 密码不合格 / 5 角色非法 / 6 写入失败
ts_create_user() {
	local user pw role home salt hash tmp
	user="$1"
	pw="$2"
	role="$3"
	ts_safe_name "$user" || return 2
	ts_user_exists "$user" && return 3
	[ ${#pw} -ge "$TS_MIN_PASSWORD" ] && [ ${#pw} -le 128 ] || return 4
	case "$pw" in *[![:print:]]*) return 4 ;; esac
	case "$role" in admin|user) ;; *) return 5 ;; esac

	home="$TS_USERS_DIR/$user"
	mkdir -p "$home/progress" 2>/dev/null || return 6
	salt="$(ts_new_salt)"
	hash="$(ts_hash_password "$salt" "$pw")"
	[ -n "$hash" ] || return 6
	tmp="$home/.account.$$"
	{
		printf 'role=%s\n' "$role"
		printf 'salt=%s\n' "$salt"
		printf 'hash=%s\n' "$hash"
		printf 'created=%s\n' "$(date +%s)"
	} > "$tmp" || { rm -f "$tmp"; return 6; }
	mv -f "$tmp" "$home/account" || { rm -f "$tmp"; return 6; }
	chmod 600 "$home/account" 2>/dev/null
	chmod 700 "$home" 2>/dev/null
	return 0
}

ts_set_password() {
	local user pw salt hash account tmp
	user="$1"
	pw="$2"
	ts_user_exists "$user" || return 3
	[ ${#pw} -ge "$TS_MIN_PASSWORD" ] && [ ${#pw} -le 128 ] || return 4
	case "$pw" in *[![:print:]]*) return 4 ;; esac
	salt="$(ts_new_salt)"
	hash="$(ts_hash_password "$salt" "$pw")"
	[ -n "$hash" ] || return 6
	account="$TS_USERS_DIR/$user/account"
	tmp="$TS_USERS_DIR/$user/.account.$$"
	{
		printf 'role=%s\n' "$(ts_user_role "$user")"
		printf 'salt=%s\n' "$salt"
		printf 'hash=%s\n' "$hash"
		printf 'created=%s\n' "$(sed -n 's/^created=//p' "$account" | head -n 1)"
	} > "$tmp" || { rm -f "$tmp"; return 6; }
	mv -f "$tmp" "$account" || { rm -f "$tmp"; return 6; }
	chmod 600 "$account" 2>/dev/null
	return 0
}

# ---- 会话 ----

ts_delete_sessions_for() {
	local user sf
	user="$1"
	for sf in "$TS_SESSION_DIR"/*; do
		[ -f "$sf" ] || continue
		[ "$(sed -n '1p' "$sf")" = "$user" ] && rm -f "$sf"
	done
}

ts_delete_user_data() {
	local user
	user="$1"
	ts_safe_name "$user" || return 2
	rm -rf "$TS_USERS_DIR/$user"
	ts_delete_sessions_for "$user"
}

# 创建会话并输出 token（同时设置全局 TS_TOKEN）
ts_create_session() {
	local user role token exp
	user="$1"
	role="$2"
	token="$(ts_new_token)"
	[ ${#token} -eq 64 ] || return 1
	exp=$(( $(date +%s) + TS_SESSION_TTL ))
	printf '%s\n%s\n%s\n' "$user" "$role" "$exp" > "$TS_SESSION_DIR/$token" || return 1
	chmod 600 "$TS_SESSION_DIR/$token" 2>/dev/null
	TS_TOKEN="$token"
	printf '%s' "$token"
}

ts_token_from_request() {
	local token
	token=""
	if [ -n "${HTTP_X_AUTH_TOKEN:-}" ]; then
		token="$HTTP_X_AUTH_TOKEN"
	elif [ -n "${HTTP_AUTHORIZATION:-}" ]; then
		token="$(printf '%s' "$HTTP_AUTHORIZATION" | sed -n 's/^[Bb]earer[[:space:]][[:space:]]*//p')"
	fi
	if [ -z "$token" ] && [ -n "${HTTP_COOKIE:-}" ]; then
		token="$(printf '%s' "$HTTP_COOKIE" | tr ';' '\n' \
			| sed -n 's/^[[:space:]]*tingshu_session=\([0-9a-fA-F]*\).*/\1/p' | head -n 1)"
	fi
	printf '%s' "$token"
}

# 从请求里恢复会话，成功则设置 TS_USER / TS_ROLE / TS_TOKEN
ts_load_session() {
	local token sf user role exp
	token="$(ts_token_from_request)"
	case "$token" in
		''|*[!0-9a-fA-F]*) return 1 ;;
	esac
	[ ${#token} -eq 64 ] || return 1
	sf="$TS_SESSION_DIR/$token"
	[ -f "$sf" ] || return 1
	user="$(sed -n '1p' "$sf")"
	role="$(sed -n '2p' "$sf")"
	exp="$(sed -n '3p' "$sf")"
	case "$exp" in ''|*[!0-9]*) rm -f "$sf"; return 1 ;; esac
	[ "$exp" -gt "$(date +%s)" ] || { rm -f "$sf"; return 1; }
	ts_user_exists "$user" || { rm -f "$sf"; return 1; }
	# 角色以账号文件为准，避免会话里残留旧角色
	role="$(ts_user_role "$user")"
	TS_USER="$user"
	TS_ROLE="$role"
	TS_TOKEN="$token"
	return 0
}

ts_set_cookie() {
	printf 'Set-Cookie: tingshu_session=%s; Path=/; HttpOnly; SameSite=Lax; Max-Age=%s\r\n' "$1" "$TS_SESSION_TTL"
}

ts_clear_cookie() {
	printf 'Set-Cookie: tingshu_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0\r\n'
}

# ---- 鉴权 ----

ts_require_auth() {
	if [ "${TINGSHU_AUTH_DISABLED:-0}" = "1" ]; then
		TS_USER="admin"
		TS_ROLE="admin"
		return 0
	fi
	ts_load_session && return 0
	ts_fail 401 "unauthorized"
}

ts_require_admin() {
	ts_require_auth
	[ "$TS_ROLE" = "admin" ] && return 0
	ts_fail 403 "forbidden"
}

# 首次创建管理员时，把旧的单用户数据迁到这个账号下
ts_migrate_legacy() {
	local user home
	user="$1"
	home="$TS_USERS_DIR/$user"
	[ -d "$home" ] || return 1
	mkdir -p "$home/progress" 2>/dev/null
	if [ -d /etc/tingshu/progress ] && [ -z "$(ls -A "$home/progress" 2>/dev/null)" ]; then
		cp -a /etc/tingshu/progress/. "$home/progress/" 2>/dev/null
	fi
	[ -f /etc/tingshu/collections.json ] && [ ! -f "$home/collections.json" ] \
		&& cp -f /etc/tingshu/collections.json "$home/collections.json" 2>/dev/null
	[ -f /etc/tingshu/pausemarks.json ] && [ ! -f "$home/pausemarks.json" ] \
		&& cp -f /etc/tingshu/pausemarks.json "$home/pausemarks.json" 2>/dev/null
	[ -f /etc/tingshu/layout.json ] && [ ! -f "$home/layout.json" ] \
		&& cp -f /etc/tingshu/layout.json "$home/layout.json" 2>/dev/null
	chmod 700 "$home" "$home/progress" 2>/dev/null
	return 0
}
