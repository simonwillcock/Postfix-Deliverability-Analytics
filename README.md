Postfix Deliverability Analytics
=========

Due to the smtp protocol imperfection there are very limited possibilities for a mail client to learn much about what happens with a message after being sent. However we can resolve this information from logs of Postfix smtp server that is logging the entire course of message delivery process. PDA runs on the same host and it is parsing postfix log files and indexing data so that mail clients can query PDA over http and restful interface for whatever information it is interested in. 

PDA general info
----

  - Standalone application written in Scala, using Akka and MapDB as it's core dependencies
  - Apart from HttpServer single-threaded pool there are 4 more threads/actors : 
    - Superviser
    - Indexer - receives relevant log records and indexes / stores them to MapDB.
    - Tailer -  controls another thread that tails log files. It is blocked all the time
  - Memory footprint is from 20 - 400 MG RAM depending on databaze size that affects memory size required for handling restful queries
  - Database is encrypted and http server supports only basic authentication
  - Prefer using dedicated postfix server being shared by a set of PDA client applications. Do not expect PDA to be able of handling tens of GigaBytes of log files. On a dedicated postfix server PDA is able to index and serve around 5GB of uncompressed log files. It is not optimized and tested beyond that.
  - PDA is using bounce-regex-list.xml file to gather regular expressions for categorizing bounces by messages. Once in a while it needs to be updated with regular expression for newly encountered bounce messages that was not categorized by current rules. Beware that amount of regular expressions and their complexity influence indexing speed because it might be executed in order of magnitude of 7
  - PDA's user interface is served by HttpServer running on localhost:1523 by default
  - PDA's initialization has a several steps
    1. regex-bounce-list.xml file is processed 
    2. log files that were backed up by logrotate are indexed
    3. tailing of the actual log file that is being written to starts, indexing all its current and future content
    4. HttpServer starts listening http requests

Postfix setting
----

  - All necessary configuration regarding to postfix can be set in /etc/postfix/main.cf
    - deferred queue tuning - when postfix is unable to deliver a message due to a soft bounce, it attempts to do so again using interval where next value is a double of the previous. By default it is trying to do so too frequently which might produce GigaBytes of data. Desired time serie would rather look like this : 4m, 8m, 16m, 32m, 1h, 2h, 4h, 8h, 16h, 32h, 64h 
        - minimal_backoff_time = 240s
        - maximal_backoff_time = 40h
        - maximal_queue_lifetime = 6d
    - enable_long_queue_ids = yes
        - this is an essential setting that makes queue IDs unique which eliminates records duplication
    - everything else can be set in PDA's agent.conf file, especially :
        -  logs.dir = "/var/log/"
        -  tailed-log-file = "mail.log"
        -  rotated-file = "mail.log.1"
        -  rotated-file-pattern = "mail\\.log\\.(\\d{1,3}).*"
        -  max-file-size-to-index : 1000

SMTP client setting
----

Client applications must identify themselves so that PDA is able to associate message queues with corresponding smtp clients.
  - there are 2 ways how to do that
    - **simple** : override Message-ID header with value of this format :
```
"<" + "cid".hashCode() + '.' + id + '.' + RND.nextInt(1000) + '@' + clientId + ">"
```
    - **complicated** : consists in adding a new header "client-id" to a message and setting up postfix to log these headers so that PDA can see that. Header_checks and logging requires creating a configuration file /etc/postfix/maps/header_checks which makes postfix log messages that have a "client-id" MIME header
```
"/^client-id:.* / INFO"
```

Syslog setting
------

Syslog is logging timestamps without miliseconds which is not quite useful
  - /etc/rsyslog.conf 
    - define MailLogFormat template
```
$template MailLogFormat,"%timegenerated:1:4:date-rfc3339% %timegenerated:1:6:date-rfc3164-buggyday% %timegenerated:12:23:date-rfc3339% %source% %syslogtag%%msg%\n"
``` 
    - tell syslog to use template MailLogFormat for logging into mail.* log files
```
mail.*              -/var/log/mail.log;MailLogFormat
```

Logrotate setting
------

Logrotate is periodically rotating log files so that the log file will be renamed and compressed as follows : 
  - mail.log -> mail.log.1 -> mail.log.1.gz -> mail.log.1.gz
  - these settings makes it easier for PDA to process everything. Property 'size 100MB' doesn't allow log files grow more then 100MB 
  - /etc/logrotate.d/rsyslog
```
/var/log/mail.log {
        rotate 100
	    size 100M
	    missingok
	    notifempty
	    compress
	    delaycompress
	    sharedscripts
	    postrotate
		    invoke-rc.d rsyslog reload > /dev/null
	    endscript
}
```

PDA setting
-----

All PDA settings are available in application.conf

    