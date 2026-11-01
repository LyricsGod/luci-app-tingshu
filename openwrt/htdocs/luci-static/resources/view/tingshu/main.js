'use strict';
'require view';
'require form';
'require uci';
'require fs';
'require ui';

return view.extend({
	load: function() {
		return uci.load('tingshu');
	},

	render: function() {
		var m, s, o;
		var self = this;

		m = new form.Map('tingshu', _('听书'),
			_('把路由器上的有声书目录做成一个网页书架。手机、平板、电脑在局域网里打开就能听，' +
			  '支持断点记忆、倍速、定时关闭和合集；听书进度保存在各自的浏览器里。'));

		s = m.section(form.NamedSection, 'main', 'tingshu');
		s.anonymous = true;
		s.addremove = false;

		o = s.option(form.Flag, 'enabled', _('启用服务'),
			_('关闭后停止监听端口，网页将无法访问。'));
		o.default = '1';
		o.rmempty = false;

		o = s.option(form.Value, 'port', _('网页端口'),
			_('浏览器用 http://路由器IP:端口/ 访问。默认 8686，修改后需要保存并应用。'));
		o.datatype = 'port';
		o.placeholder = '8686';
		o.default = '8686';
		o.rmempty = false;

		o = s.option(form.Value, 'books_dir', _('书架目录'),
			_('路由器上存放有声书的路径。每个子文件夹算一本书，文件夹名就是书名。' +
			  '常见位置：U 盘 /mnt/sda1、移动硬盘 /mnt/sdb1，或用 mount 挂进来的网络共享。'));
		o.placeholder = '/mnt/audiobooks';
		o.default = '/mnt/audiobooks';
		o.rmempty = false;

		o = s.option(form.DummyValue, '_url', _('访问地址'));
		o.rawhtml = true;
		o.cfgvalue = function() {
			var port = uci.get('tingshu', 'main', 'port') || '8686';
			var url = 'http://' + window.location.hostname + ':' + port + '/';
			return '<a href="' + url + '" target="_blank" rel="noreferrer">' + url + '</a>';
		};

		o = s.option(form.Button, '_scan', _('书库维护'));
		o.inputtitle = _('重新扫描书库');
		o.inputstyle = 'apply';
		o.onclick = function() {
			return self.handleScan();
		};

		o = s.option(form.Button, '_restart', ' ');
		o.inputtitle = _('重启服务');
		o.inputstyle = 'reload';
		o.onclick = function() {
			return fs.exec('/etc/init.d/tingshu', [ 'restart' ]).then(function() {
				ui.addNotification(null, E('p', {}, _('服务已重启。')), 'info');
			}).catch(function(e) {
				ui.addNotification(null, E('p', {}, _('重启失败：%s').format(e.message)), 'error');
			});
		};

		return m.render();
	},

	handleSaveApply: function(ev, mode) {
		/* 保存后显式重启服务：不依赖 reload_config 的 config.change 通知，
		   某些固件上它不会为第三方包投递事件 */
		return this.super('handleSaveApply', [ev, mode]).then(function() {
			return fs.exec('/etc/init.d/tingshu', [ 'restart' ]);
		}).then(function(res) {
			if (res.code !== 0)
				ui.addNotification(null,
					E('p', {}, _('设置已保存，但服务重启返回 %d，请点「重启服务」再试。').format(res.code)),
					'warning');
		}).catch(function(e) {
			ui.addNotification(null,
				E('p', {}, _('设置已保存，但重启服务失败：%s').format(e.message)), 'warning');
		});
	},

	handleScan: function() {
		ui.showModal(_('正在扫描书库'), [
			E('p', { 'class': 'spinning' }, _('正在读取书架目录，书多的时候需要几秒钟…'))
		]);

		return fs.exec('/usr/lib/tingshu/scan.sh').then(function(res) {
			ui.hideModal();

			if (res.code !== 0) {
				ui.addNotification(null,
					E('p', {}, _('扫描失败，返回码 %d。').format(res.code)), 'error');
				return;
			}

			var info = null;
			try {
				var lines = (res.stdout || '').trim().split('\n');
				info = JSON.parse(lines[lines.length - 1]);
			} catch (e) { /* 解析失败就不显示数量 */ }

			if (info) {
				ui.addNotification(null, E('p', {},
					_('扫描完成：%d 本书，%d 个音频。').format(info.books || 0, info.tracks || 0)), 'info');
			} else {
				ui.addNotification(null, E('p', {}, _('扫描完成。')), 'info');
			}

			if (res.stderr)
				console.warn('tingshu scan:', res.stderr);
		}).catch(function(e) {
			ui.hideModal();
			ui.addNotification(null,
				E('p', {}, _('扫描失败：%s').format(e.message)), 'error');
		});
	}
});
