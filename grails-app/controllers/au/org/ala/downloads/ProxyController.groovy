package au.org.ala.downloads

import au.org.ala.web.AlaSecured
import grails.converters.JSON
import groovy.time.TimeCategory
import org.codehaus.groovy.grails.web.servlet.GrailsApplicationAttributes

class ProxyController {

    public static final String ACCEPT = "Accept"
    public static final String ACCEPT_CHARSET = "Accept-Charset"
    public static final String ACCEPT_ENCODING = "Accept-Encoding"
    public static final String ACCEPT_LANGUAGE = "Accept-Language"
    public static final String ACCEPT_DATETIME = "Accept-Datetime"
    public static final String IF_RANGE = "If-Range"
    public static final String RANGE = "Range"
    public static final String IF_MODIFIED_SINCE = "If-Modified-Since"
    public static final String IF_UNMODIFIED_SINCE = "If-Unmodified-Since"
    public static final String IF_MATCH = "If-Match"
    public static final String IF_NONE_MATCH = "If-None-Match"
    public static final String CONTENT_LANGUAGE = "Content-Language"
    public static final String CONTENT_MD5 = "Content-MD5"
    public static final String CONTENT_RANGE = "Content-Range"
    public static final String ETAG = "ETag"
    public static final String LAST_MODIFIED = "Last-Modified"
    public static final String CONTENT_ENCODING = 'Content-Encoding'
    public static final String ACCEPT_RANGES = "Accept-Ranges"
    public static final String PRAGMA = 'Pragma'
    public static final String CACHE_CONTROL = 'Cache-Control'
    public static final String EXPIRES = 'Expires'
    public static final String USER_AGENT = "User-Agent"
    public static final String CONTENT_DISPOSITION = 'Content-Disposition'

    def loggerService, proxyService

    def download (DownloadCommand command) {

        if (command.hasErrors()) {
            flash.errors = command
            redirect(controller: "home")
            return null
        }

        def downloadInstance = Download.get(command.id)
        if (!downloadInstance) {
            flash.message = message(code: 'default.not.found.message', args: [message(code: 'download.label', default: 'Download'), id])
            redirect(controller: "home")
            return null
        }

        final dataUri = downloadInstance.fileUri
        final metaUri = downloadInstance.metadataUri
        final userIP = request.getRemoteAddr()
        final userEmail = command.userEmail
        final comment = command.comment?:""
        final reasonTypeId = command.reasonTypeId

        // asynchronously log download after it completes
        httpProxy(dataUri) { status, contentRange ->
            // only log a get that succeeds or a partial content if the range contains the first byte
            if (request.method == "GET" && (status == 200 || (status == 206 && contentRange.contains('bytes 0-')))) {
                LogEvent.async.task {
                    try {
                        loggerService.addProxiedDownloadEvent(dataUri, metaUri, userIP, userEmail, comment, reasonTypeId)
                    } catch (e) {
                        log.error("Error adding LogEvent for ${dataUri} download", e)
                    }
                }
            }
        }

        return null
    }

    private def httpProxy(String dataUri, Closure onSuccess) {
        // Disable Grails View Rendering for this request
        def webRequest = request.getAttribute(GrailsApplicationAttributes.WEB_REQUEST)
        webRequest.setRenderView(false)


        final a = request.getHeader(ACCEPT)
        final ac = request.getHeader(ACCEPT_CHARSET)
        final ae = request.getHeader(ACCEPT_ENCODING)
        final al = request.getHeader(ACCEPT_LANGUAGE)
        final ad = request.getHeader(ACCEPT_DATETIME)

        final ir = request.getHeader(IF_RANGE)
        final r = request.getHeader(RANGE)

        final ims = request.getDateHeader(IF_MODIFIED_SINCE)
        final ius = request.getHeader(IF_UNMODIFIED_SINCE)
        final im = request.getHeader(IF_MATCH)
        final inm = request.getHeader(IF_NONE_MATCH)

        // synchronously proxy download
        final url = dataUri.toURL()
        final stream
        final urlConnection
        try {
            final rc
            final cd
            urlConnection = url.openConnection()
            if (urlConnection instanceof HttpURLConnection) {
                urlConnection.asType(HttpURLConnection).with {
                    use(TimeCategory) {
                        connectTimeout = 10.seconds.toMilliseconds()
                        readTimeout = 10.seconds.toMilliseconds()
                    }
                    requestMethod = request.method

                    setRequestProperty(USER_AGENT, ProxyService.USER_AGENT)
                    setRequestProperty(ACCEPT_ENCODING, 'identity')

                    if (a) setRequestProperty(ACCEPT, a)
                    if (ac) setRequestProperty(ACCEPT_CHARSET, ac)
                    if (ad) setRequestProperty(ACCEPT_DATETIME, ad)
                    if (ae) setRequestProperty(ACCEPT_ENCODING, ae)
                    if (al) setRequestProperty(ACCEPT_LANGUAGE, al)

                    if (ir) setRequestProperty(IF_RANGE, ir)
                    if (r) setRequestProperty(RANGE, r)

                    if (ims != -1) ifModifiedSince = ims
                    if (ius) setRequestProperty(IF_UNMODIFIED_SINCE, ius)
                    if (im) setRequestProperty(IF_MATCH, im)
                    if (inm) setRequestProperty(IF_NONE_MATCH, inm)

                    rc = responseCode
                    cd = getHeaderField(CONTENT_DISPOSITION)

                    if ((200..299).contains(responseCode)) {
                        stream = inputStream
                    } else {
                        stream = errorStream
                    }

                }
            } else {
                rc = 200
                cd = null
                stream = urlConnection.inputStream
            }

            final ce = urlConnection.contentEncoding
            final cl = urlConnection.contentLength
            final ct = urlConnection.contentType
            final lm = urlConnection.lastModified
            final clang = urlConnection.getHeaderField(CONTENT_LANGUAGE)
            final cmd5 = urlConnection.getHeaderField(CONTENT_MD5)
            final cr = urlConnection.getHeaderField(CONTENT_RANGE)
            final etag = urlConnection.getHeaderField(ETAG)
            final ar = urlConnection.getHeaderField(ACCEPT_RANGES)
            final pragma = urlConnection.getHeaderField(PRAGMA)
            final cc = urlConnection.getHeaderField(CACHE_CONTROL)
            final expires = urlConnection.getHeaderField(EXPIRES)

            final path = url.getPath()
            final filename = proxyService.getFilenameForProxiedDownload(path, cd)

            response.status = rc
            response.contentLength = cl
            response.contentType = ct
            if (ce) response.setHeader(CONTENT_ENCODING, ce)
            if (ar) response.setHeader(ACCEPT_RANGES, ar)
            if (lm) response.setDateHeader(LAST_MODIFIED, lm)
            if (etag) response.setHeader(ETAG, etag)
            if (clang) response.setHeader(CONTENT_LANGUAGE, clang)
            if (cmd5) response.setHeader(CONTENT_MD5, cmd5)
            if (cr) response.setHeader(CONTENT_RANGE, cr)
            if (cc) response.setHeader(CACHE_CONTROL, cc)
            if (expires) response.setHeader(EXPIRES, expires)
            if (pragma) response.setHeader(PRAGMA, pragma)


            response.setHeader CONTENT_DISPOSITION, "attachment; filename=${filename}"
            response.outputStream << stream

            onSuccess(rc, cr)

        } catch(Exception e) {
            log.error("Exception while attempting to proxy ${dataUri}", e)
            response.sendError(500)
        } finally {
            try {
                stream?.close()
            } catch (IOException e) {
                log.error("Exception closing stream for URL ${dataUri}", e)
            }
            if (urlConnection && urlConnection instanceof HttpURLConnection) {
                urlConnection.disconnect()
            }
        }
    }

    def readFile (Long id) {
        def downloadInstance = Download.get(id)
        if (!downloadInstance) {
            flash.message = message(code: 'default.not.found.message', args: [message(code: 'download.label', default: 'Download'), id])
            redirect(controller: "download")
            return
        }

        try {
            def fileObj = new File(downloadInstance.fileUri);
            def inputStream = fileObj.newInputStream()

            // log to ala-logger
            //LogEventVO vo_reason = new LogEventVO(1002, params.reasonTypeId?:0, 0, params.email?:"", params.comment?:"", request.getRemoteAddr(), null);
            //log (RestLevel.REMOTE, vo_reason);
            params.userIP = request.getRemoteAddr()
            loggerService.addDownloadEvent(downloadInstance, params)  // queue

            response.setHeader "Content-disposition", "attachment; filename=${fileObj.name}"
            response.contentType = "${downloadInstance.mimeType}"
            response.contentLength = fileObj.length() //downloadInstance.fileSize.toInteger()
            response.outputStream << inputStream
            response.outputStream.flush()
        } catch (FileNotFoundException fe) {
            log.error fe.localizedMessage, fe
            flash.message = message(code: 'default.not.found.message', args: [message(code: 'download.label', default: 'Download file'), id])
            redirect(controller: "download")
            return
        }

    }

}
