package com.minion.gui.input;

import javafx.scene.input.KeyCode;
import org.junit.Test;

import static org.junit.Assert.*;

/** 发送键判定与文案：纯静态逻辑，可脱离 JavaFX 单测（KeyCode 为枚举，无需 Toolkit） */
public class InputViewKeyTest {
    @Test public void repeatedEnterAfterSendMustNeverBecomeStop() {
        assertEquals(InputView.BtnMode.SEND, InputView.keyboardMode(false,false,true));
        assertEquals(InputView.BtnMode.SEND_DIM, InputView.keyboardMode(true,false,false));
        assertEquals(InputView.BtnMode.STOP, InputView.buttonMode(true,false,false));
    }
    @Test public void keyboardStillSupportsGuidanceAndAnswers() {
        assertEquals(InputView.BtnMode.SUPPLEMENT, InputView.keyboardMode(true,false,true));
        assertEquals(InputView.BtnMode.ANSWER, InputView.keyboardMode(true,true,true));
        assertEquals(InputView.BtnMode.ANSWER_DIM, InputView.keyboardMode(true,true,false));
    }

    // ===== isSendKey：默认模式（Ctrl+Enter 发送） =====

    @Test public void defaultMode_ctrlEnter_sends() {
        assertTrue(InputView.isSendKey(KeyCode.ENTER, true, false, false, false, false));
    }

    @Test public void defaultMode_plainEnter_notSend() {
        assertFalse(InputView.isSendKey(KeyCode.ENTER, false, false, false, false, false));
    }

    @Test public void defaultMode_ctrlShiftEnter_notSend() {
        // 与 KeyCodeCombination(ENTER, CONTROL_DOWN) 精确修饰键语义一致：Ctrl 按下时 Shift 须未按下
        assertFalse(InputView.isSendKey(KeyCode.ENTER, true, true, false, false, false));
    }

    @Test public void defaultMode_nonEnterKey_notSend() {
        assertFalse(InputView.isSendKey(KeyCode.A, true, false, false, false, false));
    }

    // ===== isSendKey：Enter 发送模式 =====

    @Test public void enterSendsMode_plainEnter_sends() {
        assertTrue(InputView.isSendKey(KeyCode.ENTER, false, false, false, false, true));
    }

    @Test public void enterSendsMode_ctrlEnter_notSend() {
        assertFalse(InputView.isSendKey(KeyCode.ENTER, true, false, false, false, true));
    }

    @Test public void enterSendsMode_shiftEnter_notSend() {
        assertFalse(InputView.isSendKey(KeyCode.ENTER, false, true, false, false, true));
    }

    @Test public void enterSendsMode_nonEnterKey_notSend() {
        assertFalse(InputView.isSendKey(KeyCode.A, false, false, false, false, true));
    }

    // ===== sendKeyLabel =====

    @Test public void sendKeyLabel_defaultMode() {
        assertEquals("Ctrl+Enter", InputView.sendKeyLabel(false));
    }

    @Test public void sendKeyLabel_enterSendsMode() {
        assertEquals("Enter", InputView.sendKeyLabel(true));
    }
}
