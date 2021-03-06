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

package iudex.da;

import iudex.core.ContentKeys;
import iudex.core.VisitURL;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.sql.DataSource;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gravitext.htmap.UniMap;

public class ContentReader
{
    public ContentReader( DataSource source, ContentMapper mapper )
    {
        _mapper = mapper;
        _dsource = source;
    }

    public ContentMapper mapper()
    {
        return _mapper;
    }

    public DataSource dataSource()
    {
        return _dsource;
    }

    public void setPriorityAdjusted( boolean isAdjusted )
    {
        _isPriorityAdjusted = isAdjusted;
    }

    public void setIsolationLevel( int isolationLevel )
    {
        _isolationLevel = isolationLevel;
    }

    public int isolationLevel()
    {
        return _isolationLevel;
    }

    /**
     * Set max number of retries, not including the initial try.
     * Default: 3
     */
    public void setMaxRetries( int count )
    {
        _maxRetries = count;
    }

    /**
     * Set PostgreSQL explicit LOCK compatible mode for the Urls
     * table. Currently this only used by selectWithRetry, but it may
     * extended to other methods in the future.
     */
    public void setLockMode( String mode )
    {
        _lockMode = mode;
    }

    /**
     * Set maximum first-retry back-off delay in milliseconds. Actual
     * delay is a random value from 0..(value * 2^tries) for each
     * retry.  Default: 0 : no delay
     */
    public void setBackoff( int milliseconds )
    {
        _backoff = milliseconds;
    }

    /**
     * Read map's URL and update map if found.
     */
    public void read( UniMap map ) throws SQLException
    {
        UniMap update = read( map.get( ContentKeys.URL ) );
        if( update != null ) {
            map.putAll( update );
        }
    }

    public UniMap read( VisitURL url ) throws SQLException
    {
        final StringBuilder qb = new StringBuilder( 256 );
        qb.append( "SELECT " );
        mapper().appendFieldNames( qb );
        qb.append( " FROM urls WHERE uhash = ?;" );

        List<UniMap> list = select( qb.toString(), url.uhash() );

        if( list.size() == 1 ) return list.get( 0 );
        if( list.size() > 1 ) {
            throw new IllegalStateException(
               "Multiple url rows returned for uhash=" + url.uhash() );
        }
        return null;
    }

    public List<UniMap> selectWithRetry( String query )
        throws SQLException, InterruptedException
    {
        Connection conn = dataSource().getConnection();
        try {
            conn.setAutoCommit( false );
            conn.setTransactionIsolation( isolationLevel() );

            List<UniMap> results = null;
            int tries = 0;
            retry: while( true ) {
                try {
                    ++tries;
                    lock( conn );
                    QueryRunner runner = new QueryRunner( _dsource );
                    results = runner.query( conn, query, new MapHandler() );
                    conn.commit();
                    break retry;
                }
                catch( SQLException x ) {
                    if( handleError( tries, x ) ) {
                        conn.rollback();
                        continue retry;
                    }
                    throw x;
                }
            }

            if( tries > 1 ) {
                _log.info( "Query succeeded only after {} attempts", tries );
            }
            return results;
        }
        catch( InterruptedException x ) {
            Thread.currentThread().interrupt();
            return null;
        }
        finally {
            if( conn != null ) conn.close();
        }
    }

    public List<UniMap> select( String query, Object... params )
        throws SQLException
    {
        QueryRunner runner = new QueryRunner( _dsource );

        return runner.query( query, new MapHandler(), params );
    }

    /**
     * Unreserve the specified contents by url.uhash.  Note that while
     * this is not strictly a read operation, it is closely associated to
     * select with reserve and so included here.
     * @return the number of updates.
     */
    public int unreserve( Iterable<UniMap> contents ) throws SQLException
    {
        return update( formatUnreserve( contents ) );
    }

    public int update( String query, Object... params )
        throws SQLException
    {
        Connection conn = dataSource().getConnection();
        try {
            conn.setAutoCommit( false );
            conn.setTransactionIsolation( isolationLevel() );

            int count = 0;
            int tries = 0;
            retry: while( true ) {
                try {
                    ++tries;
                    QueryRunner runner = new QueryRunner( _dsource );
                    count = runner.update( query, params );
                    conn.commit();
                    break retry;
                }
                catch( SQLException x ) {
                    if( handleError( tries, x ) ) {
                        conn.rollback();
                        continue retry;
                    }
                    throw x;
                }
            }

            if( tries > 1 ) {
                _log.info( "Update succeeded only after {} attempts", tries );
            }
            return count;
        }
        catch( InterruptedException x ) {
            Thread.currentThread().interrupt();
            return 0;
        }
        finally {
            if( conn != null ) conn.close();
        }
    }

    protected void lock( Connection conn ) throws SQLException
    {
        if( _lockMode != null ) {
            PreparedStatement stmt = null;
            try {
                String lock = "LOCK TABLE urls IN " + _lockMode + " MODE;";
                stmt = conn.prepareStatement( lock );
                stmt.executeUpdate();
            }
            finally {
                if( stmt != null ) stmt.close();
            }
        }
    }

    /**
     * Return true if a retry should be made, by inspection of the SQLException
     * and number of tries already attempted. Log accordingly. Override to reset
     * any state before a retry.
     */
    protected boolean handleError( int tries, SQLException x )
        throws InterruptedException
    {
        if( tries <= _maxRetries ) {
            SQLException s = x;
            while( s != null ) {
                String state = s.getSQLState();
                // Retry PostgreSQL Unique Key (i.e. uhash) violation or any
                // Transaction Rollback
                if( ( state != null ) &&
                    ( state.equals( "23505" ) ||
                      state.startsWith( "40" ) ) ) {
                    int delay = 0;
                    if( _backoff > 0 ) {
                        delay = _random.nextInt( _backoff * (1 << (tries-1)) );
                        Thread.sleep( delay );
                    }

                    _log.debug( "Retry {} after: {}ms, ({}) {}",
                                tries, delay, state, s.getMessage() );
                    return true;
                }
                s = s.getNextException();
            }
        }

        SQLException s = x;
        while( s != null ) {
            _log.error( "Last try {}: ({}) {}",
                        tries, s.getSQLState(), s.getMessage() );
            s = s.getNextException();
        }

        return false;
    }

    protected final Logger log()
    {
        return _log;
    }

    protected void logError( SQLException orig )
    {
        _log.error( "On write: " + orig.getMessage() );
        SQLException x = orig;
        while( ( x = x.getNextException() ) != null ) {
            _log.error( x.getMessage() );
        }
    }

    protected final class MapHandler
        implements ResultSetHandler<List<UniMap>>
    {

        @Override
        public List<UniMap> handle( ResultSet rset ) throws SQLException
        {
            final boolean priorityAdjusted = _isPriorityAdjusted;

            ArrayList<UniMap> contents = new ArrayList<UniMap>( 128 );
            while( rset.next() ) {
                UniMap map = _mapper.fromResultSet( rset );
                if( priorityAdjusted ) {
                    map.set( DAKeys.PRIORITY_ADJUSTED, Boolean.TRUE );
                }

                contents.add( map );
            }
            return contents;
        }
    }

    private String formatUnreserve( final Iterable<UniMap> contents )
    {
        final StringBuilder qb = new StringBuilder( 512 );
        qb.append( "UPDATE urls" );
        qb.append( " SET reserved = NULL" );
        qb.append( " WHERE uhash IN (" );
        boolean first = true;
        for( UniMap r : contents ) {
            if( first ) first = false;
            else qb.append( ", " );

            final String uhash = r.get( ContentKeys.URL ).uhash();

            qb.append( '\'' );
            qb.append( uhash );
            qb.append( '\'' );
        }
        qb.append( ");" );

        return qb.toString();
    }

    private final DataSource _dsource;
    private final ContentMapper _mapper;
    private boolean _isPriorityAdjusted = false;

    private int _isolationLevel = Connection.TRANSACTION_REPEATABLE_READ;
    private int _maxRetries = 3;
    private String _lockMode = null;

    private int _backoff = 0;
    private Random _random = new Random();

    private final Logger _log = LoggerFactory.getLogger( getClass() );
}
