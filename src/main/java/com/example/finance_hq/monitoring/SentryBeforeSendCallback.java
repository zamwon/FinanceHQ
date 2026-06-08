package com.example.finance_hq.monitoring;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import org.springframework.stereotype.Component;

@Component
public class SentryBeforeSendCallback implements SentryOptions.BeforeSendCallback {

	private static final String EMAIL_REGEX = "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}";
	private static final String SCRUBBED = "[scrubbed]";

	@Override
	public SentryEvent execute(SentryEvent event, Hint hint) {
		if (event != null) {
			if (event.getMessage() != null) {
				String formatted = event.getMessage().getFormatted();
				if (formatted != null) {
					event.getMessage().setFormatted(formatted.replaceAll(EMAIL_REGEX, SCRUBBED));
				}
			}

			if (event.getBreadcrumbs() != null) {
				event.getBreadcrumbs().forEach(breadcrumb -> {
					if (breadcrumb.getMessage() != null) {
						breadcrumb.setMessage(breadcrumb.getMessage().replaceAll(EMAIL_REGEX, SCRUBBED));
					}
				});
			}

			if (event.getThrowable() != null) {
				scrubExceptions(event.getThrowable());
			}
		}
		return event;
	}

	private void scrubExceptions(Throwable throwable) {
		if (throwable == null) return;

		if (throwable.getCause() != null) {
			scrubExceptions(throwable.getCause());
		}
	}
}
