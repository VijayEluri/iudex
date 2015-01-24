/*
 * Copyright (c) 2008-2015 David Kellum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package iudex.jettyhttpclient;

import iudex.http.ContentType;
import iudex.http.ContentTypeSet;
import iudex.http.HTTPClient;
import iudex.http.HTTPSession;
import iudex.http.Header;
import iudex.http.Headers;
import iudex.http.ResponseHandler;

import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.api.*;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.Fields;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gravitext.util.Closeable;
import com.gravitext.util.ResizableByteBuffer;

public class Client
    implements HTTPClient, Closeable
{
    public static class SessionAbort extends Exception
    {
        public SessionAbort( String msg )
        {
            super( msg );
        }
    }

    public Client( HttpClient client )
    {
        _client = client;
    }

    public void setExecutor( ExecutorService executor )
    {
        _client.setExecutor( executor );
    }

    /**
     * Set the set of accepted Content Type patterns.
     */
    public void setAcceptedContentTypes( ContentTypeSet types )
    {
        _acceptedContentTypes = types;
    }

    /**
     * Set maximum length of response body accepted.
     */
    public void setMaxContentLength( int length )
    {
        _maxContentLength = length;
    }

    /**
     * Set a general, global request timeout in milliseconds. Values <= 0
     * disable this general timeout.
     */
    public void setTimeout( int milliseconds )
    {
        _timeout = milliseconds;
    }

    /**
     * @deprecate Currently a no-op.
     */
    public void setCancelOnExpire( boolean doCancel )
    {
        _doCancelOnExpire = doCancel;
    }

    public HttpClient jettyClient()
    {
        return _client;
    }

    public void start() throws RuntimeException
    {
        try {
            _client.start();
        }
        catch( RuntimeException x ) {
            throw x;
        }
        catch( Exception x ) {
            throw new RuntimeException( x );
        }
    }

    @Override
    public HTTPSession createSession()
    {
        Session session = new Session();
        session.setMaxContentLength( _maxContentLength );
        session.setAcceptedContentTypes( _acceptedContentTypes );
        return session;
    }

    @Override
    public void request( HTTPSession session, ResponseHandler handler )
    {
        ((Session) session).execute( handler );
    }

    @Override
    public void close()
    {
        try {
            dump();
            _client.stop();
        }
        catch( Exception e ) {
            _log.warn( "On close: {}", e.toString() );
            _log.debug( "On close:", e );
        }
    }

    public void dump()
    {
        // Warning: Can deadlock jetty (7 at least)
        // Allow selective logging and only dump if debug logging enabled.
        Logger log = LoggerFactory.getLogger( "iudex.jettyhttpclient.Dumper" );

        if( log.isDebugEnabled() ) {
            log.debug( "Jetty Client Dump ::\n{}", _client.dump() );
        }
    }

    private class Session
        extends HTTPSession
        implements Request.HeadersListener,
                   Response.HeadersListener,
                   Response.ContentListener,
                   Response.CompleteListener

    {
        public void addRequestHeader( Header header )
        {
            _requestedHeaders.add( header );
        }

        public List<Header> requestHeaders()
        {
            return _requestHeaders;
        }

        public int statusCode()
        {
            return _statusCode;
        }

        public String statusText()
        {
            return _statusText;
        }

        public List<Header> responseHeaders()
        {
            if( _responseHeaders != null ) {
                return _responseHeaders;
            }

            return Collections.emptyList();
        }

        public ByteBuffer responseBody()
        {
            if ( _body != null ) {
                return _body.flipAsByteBuffer();
            }
            return null;
        }

        void execute( ResponseHandler handler )
        {
            _handler.set( handler );

            Request req = _client.newRequest( url() );
            switch( method() ) {
            case GET:
                req.method( HttpMethod.GET );
                break;
            case HEAD:
                req.method( HttpMethod.HEAD );
                break;
            default:
                throw new IllegalArgumentException("Unsupported: " + method());
            }
            for( Header h : _requestedHeaders ) {
                req.header(  h.name().toString(),
                             h.value().toString() );
            }

            req.onRequestHeaders( this );
            req.onResponseHeaders( this );
            req.onResponseContent( this );

            if( _timeout > 0 ) {
                req.timeout( _timeout, TimeUnit.MILLISECONDS );
            }

            try {
                req.send( this );

                _log.debug( "request sent" );
            }
            catch( UnresolvedAddressException x ) {
                handleException( x );
            }
        }

        @Override
        public void close()
        {
            //No-op
        }

        @Override
        public void abort()
        {
            //FIXME: do nothing?
        }

        @SuppressWarnings("unused")
        public void waitForCompletion() throws InterruptedException
        {
            _latch.await();
        }

        @Override
        public void onHeaders( Request request )
        {
            _log.debug( "onHeaders: {}", request );

            _requestHeaders = new ArrayList<Header>( 8 );

            _requestHeaders.add(
                new Header("Request-Line", reconstructRequestLine( request )));
            for( HttpField f : request.getHeaders() ) {
                String value = f.getValue();
                if( value == null ) value = NO_HEADER_VALUE;
                _requestHeaders.add( new Header(f.getName(), value) );
            }

            setUrl( request.getURI().toString() );
        }

        @Override
        public void onHeaders( Response response )
        {
            for( HttpField f : response.getHeaders() ) {
                String value = f.getValue();
                if( value == null ) value = NO_HEADER_VALUE;
                _responseHeaders.add( new Header( f.getName(), value) );
            }

            _statusCode = response.getStatus();
            _statusText = response.getReason();

            _log.debug( "onHeaders, status: {} {}",
                        _statusCode, _statusText );

            if( _statusCode == 200 ) {

                //check Content-Type
                ContentType ctype = Headers.contentType( _responseHeaders );

                if( ! acceptedContentTypes().contains( ctype ) ) {
                    _statusCode = NOT_ACCEPTED;
                    _statusText = null;
                    response.abort( new SessionAbort( "NOT_ACCEPTED" ) );
                }
                else {
                    int length = Headers.contentLength( _responseHeaders );
                    if( length > maxContentLength() ) {
                        _statusCode = TOO_LARGE_LENGTH;
                        _statusText = null;
                        response.abort( new SessionAbort("TOO_LARGE_LENGTH") );
                    }
                    else {
                        _body = new ResizableByteBuffer(
                                    ( length >= 0 ) ? length : 16 * 1024 );
                    }
                }
            }
        }

        @Override
        public void onContent( Response response, ByteBuffer content )
        {
            if( _body != null ) {
                if( ( _body.position() + content.remaining() ) >
                    maxContentLength() ) {
                    _body = null;
                    _statusCode = TOO_LARGE;
                    response.abort( new SessionAbort( "TOO_LARGE" ) );
                }
                else {
                    _body.put( content );
                }
            }
            else {
                content.position( content.limit() );
                _log.debug( "Ignoring onResponseContent" );
            }
        }

        @Override
        public void onComplete( Result result )
        {
            _log.debug( "onComplete {}", result );
            if( result.getFailure() != null ) {
                handleException( result.getFailure() );
            }
            else {
                complete();
            }
        }

        private void complete()
        {
            ResponseHandler handler = _handler.getAndSet( null );

            if( handler != null ) {
                handler.sessionCompleted( this );
                _log.debug( "after sessionCompleted, notifying" );
                _latch.countDown();
            }
            else {
                _log.warn( "Redundant complete" );
            }
        }

        private void handleException( Throwable t ) throws Error
        {
            if( t instanceof Exception ) {

                if( _handler.get() == null ) {
                    if( ! (t instanceof AsynchronousCloseException ) ) {
                        _log.warn( "Exception (already handled): {}",
                                   t.toString() );
                        _log.debug( "Exception (stack)", t );
                    }
                    return;
                }

                if( ( t instanceof IllegalArgumentException ) &&
                    ( t.getCause() instanceof URISyntaxException ) ) {
                    t = t.getCause();
                }
                else if( t instanceof TimeoutException ) {
                    if( "Total timeout elapsed".equals( t.getMessage() ) ) {
                        _statusCode = TIMEOUT;
                    }
                    else {
                        _statusCode = TIMEOUT_CONNECT;
                    }
                }
                else if( t instanceof SocketTimeoutException ) {
                    _statusCode = TIMEOUT_SOCKET;
                }
                else if( ( t instanceof UnresolvedAddressException ) ||
                         ( t instanceof UnknownHostException ) ) {
                    _statusCode = UNRESOLVED;
                }
                else if( t instanceof URISyntaxException ) {
                    _statusCode = INVALID_REDIRECT_URL;
                }
                else if( t instanceof SessionAbort ) {
                    _statusText = null;
                }
                else if( t instanceof HttpResponseException ) {
                    if( t.getMessage().startsWith( "Max redirects ") ) {
                        _statusCode = MAX_REDIRECTS_EXCEEDED;
                    }
                    else {
                        _statusCode = ERROR;
                    }
                }
                else {
                    _statusCode = ERROR;
                }

                setError( (Exception) t );
                complete();
            }
            else {
                _log.error( "Session onException (Throwable): ", t );
                _statusCode = ERROR_CRITICAL;

                if( t instanceof Error) {
                    throw (Error) t;
                }
                else {
                    // Weird shit outside Exception or Error branches.
                    throw new RuntimeException( t );
                }
            }
        }

        private CharSequence reconstructRequestLine( Request request )
        {
            String method = request.getMethod().toString();
            String path = request.getURI().getRawPath();
            if( path == null || path.isEmpty() ) {
                path = "/";
            }
            String query = request.getURI().getRawQuery();

            int length = method.length() + 1;
            length += path.length();
            if( query != null ) {
                length += query.length() + 1;
            }
            StringBuilder req = new StringBuilder( length );

            req.append( method ).append( ' ' );
            req.append( path );
            if( query != null ) {
                req.append( '?' );
                req.append( query );
            }

            return req;
        }

        private AtomicReference<ResponseHandler> _handler =
            new AtomicReference<ResponseHandler>();
        private List<Header> _requestedHeaders = new ArrayList<Header>( 8 );
        private int _statusCode = STATUS_UNKNOWN;
        private String _statusText = null;
        private List<Header> _requestHeaders = null;
        private List<Header> _responseHeaders = new ArrayList<Header>( 8 );
        private ResizableByteBuffer _body = null;
        private final CountDownLatch _latch = new CountDownLatch(1);
    }

    private static final String NO_HEADER_VALUE = "";

    private final HttpClient _client;

    private int _maxContentLength = 1024 * 1024 - 1;
    private ContentTypeSet _acceptedContentTypes = ContentTypeSet.ANY;

    @SuppressWarnings("unused")
    private boolean _doCancelOnExpire = true;

    private int _timeout = 0;

    private final Logger _log = LoggerFactory.getLogger( getClass() );
}
