package com.tuktaklab.keepsy.campaign;

import org.springframework.context.ApplicationContext;

/** 시드 생성(#335)이 가상 발송 결과 갱신을 직접 부르는 통로. 시드 중에는 스케줄러를 멈추므로 발송 뒤 이걸로 결과를 정한다 */
public final class VirtualDelivery {

    private VirtualDelivery() {}

    public static void deliverPending(ApplicationContext context) {
        context.getBean(VirtualMessageSender.class).deliverPending();
    }
}
