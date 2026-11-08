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
LIB_DIR="${TINGSHU_LIB_DIR:-/usr/lib/tingshu}"
PERL="$(command -v perl 2>/dev/null)"
DUR_CACHE="/etc/tingshu/durations"
WARM_LOCK="/tmp/tingshu-warm.lock"

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

# 保证 media 指向当前书架目录：在 LuCI 改了「书架目录」却只点「重新扫描」、
# 没点「保存并应用」（不会重启服务）时，软链还指着旧路径，就会出现
# 索引是新的、封面和音频却全部 404 的情况。
MEDIA_LINK="$WWW_DIR/media"
if ! mount 2>/dev/null | grep -q " on $MEDIA_LINK "; then
	if [ "$(readlink "$MEDIA_LINK" 2>/dev/null)" != "$BOOKS_DIR" ]; then
		rm -f "$MEDIA_LINK" 2>/dev/null
		ln -sfn "$BOOKS_DIR" "$MEDIA_LINK" 2>/dev/null
	fi
fi

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

# 扫描后顺带把章节时长算好并落盘（/etc 持久，重启不丢），之后打开书籍页
# 直接命中缓存。书库常在 CIFS/SMB 上，读文件偶尔会卡死，所以：
#   · 放到后台跑，绝不拖住扫描结果
#   · 每本加 timeout，卡住就跳过
#   · 用锁避免多次扫描时重复堆积
warm_durations() {
	[ -n "$PERL" ] || return 0
	[ -f "$LIB_DIR/durations.pl" ] || return 0
	[ -f "$WARM_LOCK" ] && return 0
	: > "$WARM_LOCK" 2>/dev/null || return 0
	(
		mkdir -p "$DUR_CACHE" 2>/dev/null
		for d in "$BOOKS_DIR"/*/; do
			[ -d "$d" ] || continue
			slash="${d%/}"
			rel="${slash#"$BOOKS_DIR"/}"
			[ -n "$rel" ] || continue
			key="$(printf '%s' "$rel" | md5sum | cut -d' ' -f1)"
			[ -n "$key" ] || continue
			[ -s "$DUR_CACHE/$key.json" ] && continue
			# 有的固件 busybox 没编 timeout，这里不依赖它：预热本来就在后台跑，
			# 加上前面的锁，卡住也不会重复堆积。
			"$PERL" "$LIB_DIR/durations.pl" --books "$BOOKS_DIR" \
				--rel "$rel" --cache "$DUR_CACHE/$key.json" >/dev/null 2>&1
		done
		rm -f "$WARM_LOCK"
	) >/dev/null 2>&1 &
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

# 预热时长缓存：失败也不影响书库索引，所以放最后且忽略返回值
warm_durations

printf '{"books":%d,"tracks":%d,"root":"%s"}\n' "$book_count" "$track_count" "$(esc "$BOOKS_DIR")"
exit 0
