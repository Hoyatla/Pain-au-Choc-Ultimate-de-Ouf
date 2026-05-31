package net.irisshaders.iris.shaderpack.preprocessor;

import net.irisshaders.iris.Iris;
import org.anarres.cpp.LexerException;
import org.anarres.cpp.Preprocessor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MacroLexerCompat {
	// JCPP rejects decimal literals with leading zeroes when they contain 8 or 9 (e.g. 00900).
	private static final Pattern INVALID_OCTAL_LITERAL =
		Pattern.compile("(?<![A-Za-z0-9_\\.])0[0-9]*[89][0-9]*(?![A-Za-z0-9_\\.])");
	private static final Set<String> EMITTED_ADJUSTMENT_WARNINGS = ConcurrentHashMap.newKeySet();

	private MacroLexerCompat() {
	}

	static void addMacro(Preprocessor preprocessor, String name, String value) throws LexerException {
		try {
			preprocessor.addMacro(name, value);
		} catch (LexerException firstError) {
			String normalizedValue = normalizeInvalidOctalLiterals(value);
			if (normalizedValue.equals(value)) {
				throw firstError;
			}

			preprocessor.addMacro(name, normalizedValue);
			String warningKey = name + "\u0000" + value + "\u0000" + normalizedValue;
			if (EMITTED_ADJUSTMENT_WARNINGS.add(warningKey)) {
				Iris.logger.warn("Adjusted macro {} from '{}' to '{}' for preprocessor compatibility.", name, value, normalizedValue);
			}
		}
	}

	private static String normalizeInvalidOctalLiterals(String value) {
		Matcher matcher = INVALID_OCTAL_LITERAL.matcher(value);
		StringBuffer output = new StringBuffer(value.length());
		boolean changed = false;

		while (matcher.find()) {
			String literal = matcher.group();
			String normalized = stripLeadingZeros(literal);
			if (!literal.equals(normalized)) {
				changed = true;
			}
			matcher.appendReplacement(output, Matcher.quoteReplacement(normalized));
		}

		if (!changed) {
			return value;
		}

		matcher.appendTail(output);
		return output.toString();
	}

	private static String stripLeadingZeros(String literal) {
		int firstNonZero = 0;
		int lastIndex = literal.length() - 1;

		while (firstNonZero < lastIndex && literal.charAt(firstNonZero) == '0') {
			firstNonZero++;
		}

		return literal.substring(firstNonZero);
	}
}
