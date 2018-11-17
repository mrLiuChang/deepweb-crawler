package com.cufe.deepweb.common.http.simulate;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.CookieManager;
import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 使用HtmlUnit实现的Browser
 */
public final class HtmlUnitBrowser extends ThreadLocal<WebClient> implements WebBrowser {
    private Logger logger = LoggerFactory.getLogger(HtmlUnitBrowser.class);
    private Builder builder;
    private CookieManager cookieManager;
    private volatile boolean isLogin;
    private HtmlUnitBrowser(Builder builder){
        this.builder = builder;
        this.cookieManager = new CookieManager();//设置全局统一的cookieManager
        this.isLogin = false;//cookieManager是否保留登录信息
    }

    /**
     * override ThreadLocal的方法
     * @return
     */
    protected WebClient initialValue(){
        WebClient client = new WebClient(BrowserVersion.BEST_SUPPORTED);
        client.getOptions().setCssEnabled(false);//headless browser不需要css支持
        client.getOptions().setDownloadImages(false);//同样不需要下载图片，节省带宽
        client.getOptions().setJavaScriptEnabled(true);
        client.getOptions().setRedirectEnabled(true);
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);//当访问错误时不打出日志
        client.getOptions().setThrowExceptionOnScriptError(false);//当js运行出错不打出日志
        client.getOptions().setTimeout(builder.timeout);//设置模拟器连接网络的超时参数
        client.setAjaxController(new NicelyResynchronizingAjaxController());
        client.setCookieManager(this.cookieManager);
        return client;
    }
    /**
     * override remove方法（未实现）
     */

    /**
     * override WebBrowser的方法
     * @param loginURL 登录页面链接
     * @param username 登录用户名
     * @param password 登录密码
     * @param usernameXpath 登录用户名输入框的xpath
     * @param passwordXpath 登录密码输入框的xpath
     * @return
     */
    @Override
    public boolean login(String loginURL, String username, String password, String usernameXpath, String passwordXpath, String submitXpath) {
        WebClient client = this.get();
        HtmlTextInput userNameInput = null;//用户名输入框
        HtmlPasswordInput passwordInput = null;//密码输入框
        HtmlElement button = null;//登录按钮,最好不要限定为必须button
        try{
            HtmlPage page = client.getPage(loginURL);
            if ((!StringUtils.isBlank(usernameXpath)) && (!StringUtils.isBlank(passwordXpath)) && (!StringUtils.isBlank(submitXpath))) {//如果xpath都有指定
                List uList = page.getByXPath(usernameXpath);
                List pList = page.getByXPath(passwordXpath);
                List sList = page.getByXPath(submitXpath);
                if (!uList.isEmpty()) {
                    userNameInput = (HtmlTextInput)uList.get(0);
                    userNameInput.setText(username);
                }
                if (!pList.isEmpty()) {
                    passwordInput = (HtmlPasswordInput)pList.get(0);
                    passwordInput.setText(password);
                }
                if (!sList.isEmpty()) {
                    button = (HtmlElement) sList.get(0);
                    button.click();
                    isLogin = true;//修改登录状态为已登录
                    return true;
                }
            }//必须指定xpath，否则无法登录，后续可拓展成指定id等等
            return false;
        }catch (IOException ex) {
            logger.error("IOEception happen when get login page", ex);
            return false;
        }
    }

    @Override
    public Optional<String> getPageContent(String URL) {
        WebClient client = this.get();
        try {
            HtmlPage page = client.getPage(URL);
            return Optional.ofNullable(page.getBody().asText());
        } catch (IOException ex) {
            logger.error("IOException happen when get page content", ex);
            return Optional.empty();
        }

    }

    @Override
    public List<String> getAllLinks(String URL) {
        List<String> links = new ArrayList<>();
        WebClient client = this.get();
        try {
            HtmlPage page = client.getPage(URL);
            URL curURL = page.getUrl();//当前页面链接
            List<HtmlAnchor> anchors = page.getAnchors();
            for (HtmlAnchor anchor : anchors) {
                String anchorURL = "";
                String hrefAttr = anchor.getHrefAttribute().trim();
                if ("".equals(hrefAttr)) continue;//如果href属性为空则跳过
                if (!hrefAttr.startsWith("http")) {
                    anchorURL = new URL(curURL, hrefAttr).toString();
                }
                links.add(anchorURL);
            }
        }catch (IOException ex) {
            logger.error("IOException happen when get page content", ex);
        }
        return links;
    }

    /**
     * 获得该模拟器的cookieManager
     * @return
     */
    public CookieManager getCookieManager() {
        return this.cookieManager;
    }


    /**
     * 构造器
     */
    public static class Builder{
        private int timeout;
        public Builder(){
            timeout = 90_000;

        }

        /**
         *
         * @param timeout 超时时间/s
         * @return
         */
        public Builder setTimeout(int timeout){
            this.timeout = timeout;
            return this;
        }
        public HtmlUnitBrowser build(){
            return new HtmlUnitBrowser(this);
        }
    }
}
