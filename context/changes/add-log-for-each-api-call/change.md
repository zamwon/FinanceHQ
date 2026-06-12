---
change_id: add-log-for-each-api-call
title: Add SLF4J info log at the start of each controller method
status: implemented
created: 2026-06-12
updated: 2026-06-12
archived_at: null
---

## Notes

I want to add a slf4j info log in each controller method. Log should be placed before service is called. It should contain brief info about status for ex. when login endpoint is called then log.info("Started login call as {}, maskEmail(loginDto.getEmail)). In other api endpoints where auth is required also retrieve data about user perfoming action from AuthenticationPrincipal User user -> user.getEmail() - remember to mask it.
