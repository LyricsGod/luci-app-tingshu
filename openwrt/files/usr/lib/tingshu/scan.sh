#!/bin/sh
#
# 听书 · 书库扫描脚本
# 把「书架目录」下的有声书整理成网页端能读的 library.json
#
# 输出：$TINGSHU_WWW_DIR/library.json   （默认 /tmp/tingshu/www/library.json）
# 同时向 stdout 打印一行摘要，供 LuCI 显示结果
#

WWW_DIR="${TINGSHU_WWW_DIR:-/tmp/tingshu/www}"
BOOKS_DIR="${TINGSHU_BOOKS_DIR:-}"

if [ -z "$BOOKS_DIR" ]; then
	if [ -f /lib/functions.sh ] && [ -f /etc/config/tingshu ]; then
		. /lib/functions.sh
		config_load tingshu
		config_get BOOKS_DIR main books_dir /mnt/audiobooks
	else
		BOOKS_DIR=/mnt/audiobooks
	fi
fi

OUT="$WWW_DIR/library.json"
mkdir -p "$WWW_DIR" 2>/dev/null

TMP="$(mktemp -d /tmp/tingshu-scan.XXXXXX)" || exit 1
trap 'rm -rf "$TMP"' EXIT

BOOKS_JSON="$TMP/books.json"
: > "$BOOKS_JSON"

book_count=0
track_count=0
error_msg=""

# JSON 字符串转义（去掉换行/制表，转义反斜杠和双引号）
esc() {
	printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr -d '\r\n\t'
}

# $1 = 目录  $2 = maxdepth
list_audio() {
	find "$1" -maxdepth "$2" -type f \( \
		-iname '*.mp3' -o -iname '*.m4a' -o -iname '*.m4b' -o -iname '*.aac' -o \
		-iname '*.ogg' -o -iname '*.oga' -o -iname '*.opus' -o \
		-iname '*.wav' -o -iname '*.flac' -o -iname '*.webm' \
	\) 2>/dev/null
}

# 输出一条 track 对象（逗号由调用方控制）
# $1 = 文件绝对路径
emit_track() {
	local f path name
	f="$1"
	path="${f#"$BOOKS_DIR"/}"
	name="$(basename "$f")"
	name="${name%.*}"
	printf '{"title":"%s","path":"%s"}' "$(esc "$name")" "$(esc "$path")"
	track_count=$((track_count + 1))
}

# 输出一本书的 JSON 对象到 $BOOKS_JSON
# $1 = 绝对目录  $2 = 相对 id/书名  $3 = maxdepth
emit_book() {
	local dir rel depth list n cover base c f first
	dir="$1"
	rel="$2"
	depth="$3"

	list="$TMP/list.txt"
	list_audio "$dir" "$depth" > "$list"
	n=$(wc -l < "$list" | tr -d ' ')
	[ "$n" -gt 0 ] || return 1

	# 封面：优先常见的封面文件名
	cover=""
	base="${dir%/}"
	for c in cover.jpg cover.jpeg cover.png folder.jpg folder.png poster.jpg front.jpg 封面.jpg 封面.png; do
		if [ -f "$base/$c" ]; then
			cover="$rel/$c"
			break
		fi
	done

	printf '{"id":"%s","title":"%s"' "$(esc "$rel")" "$(esc "$rel")" >> "$BOOKS_JSON"
	[ -n "$cover" ] && printf ',"cover":"%s"' "$(esc "$cover")" >> "$BOOKS_JSON"
	printf ',"tracks":[' >> "$BOOKS_JSON"

	first=1
	while IFS= read -r f; do
		[ -n "$f" ] || continue
		[ "$first" -eq 1 ] || printf ',' >> "$BOOKS_JSON"
		first=0
		emit_track "$f" >> "$BOOKS_JSON"
	done < "$list"

	printf ']}\n' >> "$BOOKS_JSON"
	book_count=$((book_count + 1))
	return 0
}

if [ ! -d "$BOOKS_DIR" ]; then
	error_msg="书架目录不存在：$BOOKS_DIR"
else
	# 每个子文件夹 = 一本书
	for d in "$BOOKS_DIR"/*/; do
		[ -d "$d" ] || continue
		slash="${d%/}"
		rel="${slash#"$BOOKS_DIR"/}"
		emit_book "$d" "$rel" 6
	done
fi

# 直接放在书架目录里的散装音频，单独收成一本
if [ -d "$BOOKS_DIR" ]; then
	root_list="$TMP/root.txt"
	list_audio "$BOOKS_DIR" 1 > "$root_list"
	n=$(wc -l < "$root_list" | tr -d ' ')
	if [ "$n" -gt 0 ]; then
		printf '{"id":"__root__","title":"散装音频","root":true,"tracks":[' >> "$BOOKS_JSON"
		first=1
		while IFS= read -r f; do
			[ -n "$f" ] || continue
			[ "$first" -eq 1 ] || printf ',' >> "$BOOKS_JSON"
			first=0
			emit_track "$f" >> "$BOOKS_JSON"
		done < "$root_list"
		printf ']}\n' >> "$BOOKS_JSON"
		book_count=$((book_count + 1))
	fi
fi

# 把每行对象拼成数组
books_inline="$(sed -e 's/$/,/' "$BOOKS_JSON" | tr -d '\n' | sed -e 's/,$//')"

{
	printf '{"generated":%s,"root":"%s"' "$(date +%s)" "$(esc "$BOOKS_DIR")"
	[ -n "$error_msg" ] && printf ',"error":"%s"' "$(esc "$error_msg")"
	[ "$book_count" -eq 0 ] && [ -z "$error_msg" ] \
		&& printf ',"error":"%s"' "$(esc "$BOOKS_DIR 里没有找到音频文件（支持 mp3 / m4a / aac / ogg / flac / wav）")"
	printf ',"books":[%s]}\n' "$books_inline"
} > "$OUT"

printf '{"books":%d,"tracks":%d,"root":"%s"}\n' "$book_count" "$track_count" "$(esc "$BOOKS_DIR")"
exit 0
