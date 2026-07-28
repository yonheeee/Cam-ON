package com.camon.domain.game.charades.service;

import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class CharadesAnswerMatcher {

    public boolean matches(String submittedText, String answerText) {
        if (submittedText == null || answerText == null) {
            return false;
        }

        String normalizedSubmission = normalize(submittedText);
        String normalizedAnswer = normalize(answerText);
        return !normalizedSubmission.isEmpty()
            && normalizedSubmission.equals(normalizedAnswer);
    }

    public String normalize(String value) {
        if (value == null) {
            return "";
        }

        String canonical = Normalizer
            .normalize(value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(canonical.length());
        canonical.codePoints()
            .filter(CharadesAnswerMatcher::isVisibleNonWhitespace)
            .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    private static boolean isVisibleNonWhitespace(int codePoint) {
        if (Character.isWhitespace(codePoint)
            || Character.isSpaceChar(codePoint)
            || Character.isISOControl(codePoint)) {
            return false;
        }
        return Character.getType(codePoint) != Character.FORMAT;
    }
}
