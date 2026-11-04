#!/usr/bin/perl
# 听书：扫描一本有声书的每个音频文件，算出时长（秒），输出 JSON。
#
# 用法：
#   durations.pl --books /mnt/audiobooks --rel 书名 [--cache /etc/tingshu/durations/xxx.json]
#
# 只读文件头（m4a 走 moov/mvhd，mp3 走首帧 + Xing），不解码音频。
# 输出：{"path": 123.45, ...}，path 与 library.json 里的 track.path 一致。

use strict;
use warnings;
use File::Find ();

my %opt = (books => '', rel => '', cache => '');
my @argv = @ARGV;
while (@argv) {
    my $k = shift @argv;
    if ($k eq '--books') { $opt{books} = shift @argv // ''; }
    elsif ($k eq '--rel') { $opt{rel} = shift @argv // ''; }
    elsif ($k eq '--cache') { $opt{cache} = shift @argv // ''; }
}
unless ($opt{books} ne '' && $opt{rel} ne '') {
    print STDERR "usage: durations.pl --books DIR --rel NAME [--cache FILE]\n";
    exit 2;
}

my $AUDIO = qr/\.(?:mp3|m4a|m4b|aac|ogg|oga|opus|wav|flac|webm)$/i;

sub json_escape {
    my ($s) = @_;
    $s =~ s/\\/\\\\/g;
    $s =~ s/"/\\"/g;
    $s =~ s/\n/\\n/g;
    $s =~ s/\r//g;
    $s =~ s/\t/\\t/g;
    return $s;
}

# ---- m4a / mp4 ----
sub parse_mvhd {
    my ($fh, $start, $len) = @_;
    my $pos = $start;
    my $end = $start + $len;
    while ($pos + 8 <= $end) {
        seek($fh, $pos, 0) or return undef;
        my $buf = '';
        return undef unless read($fh, $buf, 8) == 8;
        my $sz = unpack('N', substr($buf, 0, 4));
        my $ty = substr($buf, 4, 4);
        if ($ty eq 'mvhd') {
            my $body = '';
            my $n = read($fh, $body, 32) // 0;
            return undef if $n < 20;
            my $ver = unpack('C', substr($body, 0, 1));
            if ($ver == 1) {
                return undef if $n < 32;
                my $ts = unpack('N', substr($body, 20, 4));
                my $du = unpack('Q>', substr($body, 24, 8));
                return $ts ? $du / $ts : undef;
            }
            my $ts = unpack('N', substr($body, 12, 4));
            my $du = unpack('N', substr($body, 16, 4));
            return $ts ? $du / $ts : undef;
        }
        last if $sz < 8;
        $pos += $sz;
    }
    return undef;
}

sub m4a_duration {
    my ($fh, $size) = @_;
    my $pos = 0;
    while ($pos + 8 <= $size) {
        seek($fh, $pos, 0) or return undef;
        my $buf = '';
        return undef unless read($fh, $buf, 16) >= 8;
        my $sz = unpack('N', substr($buf, 0, 4));
        my $ty = substr($buf, 4, 4);
        my $hdr = 8;
        if ($sz == 1) {
            return undef unless length($buf) >= 16;
            $sz = unpack('Q>', substr($buf, 8, 8));
            $hdr = 16;
        } elsif ($sz == 0) {
            $sz = $size - $pos;
        }
        return undef if $sz < $hdr;
        if ($ty eq 'moov') {
            return parse_mvhd($fh, $pos + $hdr, $sz - $hdr);
        }
        $pos += $sz;
    }
    return undef;
}

# ---- mp3 ----
my @MP3_BITRATE_V1L3 = (0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0);

sub mp3_duration {
    my ($fh, $size) = @_;
    seek($fh, 0, 0) or return undef;
    my $head = '';
    read($fh, $head, 10);
    my $audio = 0;
    if (substr($head, 0, 3) eq 'ID3' && length($head) >= 10) {
        my @b = unpack('C4', substr($head, 6, 4));
        $audio = 10 + (($b[0] & 0x7f) << 21 | ($b[1] & 0x7f) << 14 | ($b[2] & 0x7f) << 7 | ($b[3] & 0x7f));
        $audio += 10 if (unpack('C', substr($head, 5, 1)) & 0x10);
    }

    seek($fh, $audio, 0) or return undef;
    my $chunk = '';
    read($fh, $chunk, 131072);
    my $len = length($chunk);
    my $i = 0;
    my $found = -1;
    while ($i < $len - 4) {
        if ((ord(substr($chunk, $i, 1)) == 0xff) && ((ord(substr($chunk, $i + 1, 1)) & 0xe0) == 0xe0)) {
            my $b1 = ord(substr($chunk, $i + 1, 1));
            my $b2 = ord(substr($chunk, $i + 2, 1));
            my $ver = ($b1 >> 3) & 3;      # 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
            my $layer = ($b1 >> 1) & 3;    # 1 = Layer III
            my $br = ($b2 >> 4) & 0xf;
            my $sr = ($b2 >> 2) & 3;
            if ($ver != 1 && $layer == 1 && $br > 0 && $br < 15 && $sr < 3) {
                $found = $i;
                last;
            }
        }
        $i++;
    }
    return undef if $found < 0;

    my $b1 = ord(substr($chunk, $found + 1, 1));
    my $b2 = ord(substr($chunk, $found + 2, 1));
    my $b3 = ord(substr($chunk, $found + 3, 1));
    my $ver = ($b1 >> 3) & 3;
    my $br = ($b2 >> 4) & 0xf;
    my $sr_idx = ($b2 >> 2) & 3;
    my $pad = ($b2 >> 1) & 1;
    my $mono = ((($b3 >> 6) & 3) == 3) ? 1 : 0;

    my @rates = (44100, 48000, 32000);
    my $rate = $rates[$sr_idx];
    $rate /= 2 if $ver == 2;
    $rate /= 4 if $ver == 0;

    my $bitrate;
    if ($ver == 3) { $bitrate = $MP3_BITRATE_V1L3[$br]; }
    else { $bitrate = $MP3_BITRATE_V1L3[$br] / 2; }   # MPEG2/2.5 Layer3 大致是表值一半
    return undef unless $bitrate && $rate;

    my $samples = ($ver == 3) ? 1152 : 576;
    my $frame_len = int(144 * $bitrate * 1000 / $rate) + $pad;

    # Xing / Info 头（VBR）
    my $side = ($ver == 3) ? ($mono ? 17 : 32) : ($mono ? 9 : 17);
    my $xoff = $found + 4 + $side;
    if ($xoff + 12 <= $len) {
        my $tag = substr($chunk, $xoff, 4);
        if ($tag eq 'Xing' || $tag eq 'Info') {
            my $flags = unpack('N', substr($chunk, $xoff + 4, 4));
            if ($flags & 1) {
                my $frames = unpack('N', substr($chunk, $xoff + 8, 4));
                return $frames * $samples / $rate if $frames > 0;
            }
        }
    }

    # CBR 估算：去掉 ID3v1 尾巴
    my $end = $size;
    if ($end > 128) {
        seek($fh, $end - 128, 0);
        my $tail = '';
        read($fh, $tail, 3);
        $end -= 128 if $tail eq 'TAG';
    }
    my $bytes = $end - $audio;
    return undef if $bytes <= 0 || $frame_len <= 0;
    return $bytes * 8 / ($bitrate * 1000);
}

sub file_duration {
    my ($path) = @_;
    my @st = stat($path);
    return undef unless @st;
    my $size = $st[7];
    return undef if $size < 100;
    open(my $fh, '<:raw', $path) or return undef;
    my $dur;
    my $ext = $path;
    if ($ext =~ /\.(?:m4a|m4b|mp4|aac)$/i) {
        $dur = m4a_duration($fh, $size);
    } elsif ($ext =~ /\.(?:mp3)$/i) {
        $dur = mp3_duration($fh, $size);
    }
    close($fh);
    return undef unless defined $dur && $dur > 0 && $dur < 100000;
    return $dur;
}

# ---- 收集文件 ----
my $root = $opt{books};
my $rel = $opt{rel};
my $dir;
if ($rel eq '__root__') {
    $dir = $root;
} else {
    $dir = "$root/$rel";
}
unless (-d $dir) {
    print "{}";
    exit 0;
}

my @files;
if ($rel eq '__root__') {
    opendir(my $dh, $dir) or do { print "{}"; exit 0; };
    @files = map { "$dir/$_" } grep { m/$AUDIO/ && -f "$dir/$_" } readdir($dh);
    closedir($dh);
} else {
    File::Find::find({
        no_chdir => 1,
        wanted => sub {
            return unless -f $_ && m/$AUDIO/;
            my $depth = ($_ =~ tr{/}{});
            push @files, $_ if $depth < 12;
        },
    }, $dir);
}

# 缓存：目录没变就用缓存
my $cache = $opt{cache};
if ($cache && -f $cache) {
    my $cm = (stat($cache))[9] // 0;
    my $dm = (stat($dir))[9] // 0;
    if ($cm >= $dm) {
        open(my $cf, '<:raw', $cache) or do { };
        if ($cf) {
            local $/;
            my $body = <$cf>;
            close($cf);
            print $body if defined $body && $body ne '';
            if (defined $body && $body ne '') { exit 0; }
        }
    }
}

my @out;
for my $f (sort @files) {
    my $dur = file_duration($f);
    next unless defined $dur;
    my $p = $f;
    $p =~ s{^\Q$root\E/+}{};
    push @out, sprintf('"%s":%.2f', json_escape($p), $dur);
}

my $json = '{' . join(',', @out) . '}';
if ($cache) {
    my $tmp = "$cache.tmp.$$";
    if (open(my $cf, '>:raw', $tmp)) {
        print $cf $json;
        close($cf);
        rename($tmp, $cache) or unlink($tmp);
    }
}
print $json;
