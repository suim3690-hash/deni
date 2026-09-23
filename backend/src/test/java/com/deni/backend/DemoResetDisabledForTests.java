package com.deni.backend;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * 테스트 컨텍스트가 공용 DB의 시연 세션을 끝내 버리면 안 된다. Gradle뿐 아니라 IDE에서 직접 실행해도
 * 같은 보호가 걸리도록, JUnit 런처가 시작될 때 시스템 속성으로 기본 비활성화한다.
 * 시스템 속성은 application.properties보다 우선하므로 별도 설정 없이 적용된다.
 */
public class DemoResetDisabledForTests implements LauncherSessionListener {
    static final String PROPERTY = "deni.demo.reset-on-startup";

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (System.getProperty(PROPERTY) == null) {
            System.setProperty(PROPERTY, "false");
        }
    }
}
