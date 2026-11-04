include $(TOPDIR)/rules.mk

PKG_NAME:=luci-app-tingshu
PKG_VERSION:=1.6.6
PKG_RELEASE:=1
PKG_LICENSE:=MIT
PKG_MAINTAINER:=tingshu

PKG_BUILD_DIR:=$(BUILD_DIR)/$(PKG_NAME)

include $(INCLUDE_DIR)/package.mk

define Package/luci-app-tingshu
  SECTION:=luci
  CATEGORY:=LuCI
  SUBMENU:=3. Applications
  TITLE:=听书 - 局域网有声书书架
  DEPENDS:=+uhttpd +luci-base
  PKGARCH:=all
endef

define Package/luci-app-tingshu/description
 把路由器上的有声书目录做成网页书架：断点记忆、倍速、定时关闭、合集。
 手机/电脑在局域网里用浏览器打开即可，无需安装客户端。
endef

define Package/luci-app-tingshu/conffiles
/etc/config/tingshu
endef

define Package/luci-app-tingshu/postinst
#!/bin/sh
[ -n "$${IPKG_INSTROOT}" ] || {
	/etc/init.d/tingshu enable
	/etc/init.d/tingshu restart
}
exit 0
endef

define Package/luci-app-tingshu/prerm
#!/bin/sh
[ -n "$${IPKG_INSTROOT}" ] || {
	/etc/init.d/tingshu stop
	/etc/init.d/tingshu disable
}
exit 0
endef

define Build/Prepare
	mkdir -p $(PKG_BUILD_DIR)
endef

define Build/Configure
endef

define Build/Compile
endef

define Package/luci-app-tingshu/install
	$(INSTALL_DIR) $(1)/usr/share/tingshu/www
	$(INSTALL_DATA) ./src/www/index.html $(1)/usr/share/tingshu/www/
	$(INSTALL_DATA) ./src/www/styles.css $(1)/usr/share/tingshu/www/
	$(INSTALL_DATA) ./src/www/app.js $(1)/usr/share/tingshu/www/
	$(INSTALL_DATA) ./src/www/icon.svg $(1)/usr/share/tingshu/www/
	$(INSTALL_DATA) ./src/www/manifest.webmanifest $(1)/usr/share/tingshu/www/

	$(INSTALL_DIR) $(1)/usr/lib/tingshu/api
	$(INSTALL_BIN) ./files/usr/lib/tingshu/scan.sh $(1)/usr/lib/tingshu/scan.sh
	$(INSTALL_DATA) ./files/usr/lib/tingshu/auth.sh $(1)/usr/lib/tingshu/auth.sh
	$(INSTALL_DATA) ./files/usr/lib/tingshu/http.sh $(1)/usr/lib/tingshu/http.sh
	$(INSTALL_BIN) ./files/usr/lib/tingshu/api/progress $(1)/usr/lib/tingshu/api/progress
	$(INSTALL_BIN) ./files/usr/lib/tingshu/api/manage $(1)/usr/lib/tingshu/api/manage
	$(INSTALL_BIN) ./files/usr/lib/tingshu/api/collections $(1)/usr/lib/tingshu/api/collections
	$(INSTALL_BIN) ./files/usr/lib/tingshu/api/meta $(1)/usr/lib/tingshu/api/meta
	$(INSTALL_BIN) ./files/usr/lib/tingshu/api/pausemarks $(1)/usr/lib/tingshu/api/pausemarks
	$(INSTALL_BIN) ./files/usr/lib/tingshu/api/auth $(1)/usr/lib/tingshu/api/auth
	$(INSTALL_BIN) ./files/usr/lib/tingshu/api/users $(1)/usr/lib/tingshu/api/users
	$(INSTALL_BIN) ./files/usr/lib/tingshu/api/layout $(1)/usr/lib/tingshu/api/layout

	$(INSTALL_DIR) $(1)/etc/init.d
	$(INSTALL_BIN) ./files/etc/init.d/tingshu $(1)/etc/init.d/tingshu

	$(INSTALL_DIR) $(1)/etc/config
	$(INSTALL_CONF) ./files/etc/config/tingshu $(1)/etc/config/tingshu

	$(INSTALL_DIR) $(1)/etc/uci-defaults
	$(INSTALL_BIN) ./files/etc/uci-defaults/90-luci-app-tingshu $(1)/etc/uci-defaults/90-luci-app-tingshu

	$(INSTALL_DIR) $(1)/usr/share/luci/menu.d
	$(INSTALL_DATA) ./root/usr/share/luci/menu.d/luci-app-tingshu.json $(1)/usr/share/luci/menu.d/

	$(INSTALL_DIR) $(1)/usr/share/rpcd/acl.d
	$(INSTALL_DATA) ./root/usr/share/rpcd/acl.d/luci-app-tingshu.json $(1)/usr/share/rpcd/acl.d/

	$(INSTALL_DIR) $(1)/www/luci-static/resources/view/tingshu
	$(INSTALL_DATA) ./htdocs/luci-static/resources/view/tingshu/main.js $(1)/www/luci-static/resources/view/tingshu/main.js
endef

$(eval $(call BuildPackage,luci-app-tingshu))
