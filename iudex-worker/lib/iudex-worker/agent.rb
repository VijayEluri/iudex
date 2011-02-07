#--
# Copyright (c) 2008-2011 David Kellum
#
# Licensed under the Apache License, Version 2.0 (the "License"); you
# may not use this file except in compliance with the License.  You
# may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
# implied.  See the License for the specific language governing
# permissions and limitations under the License.
#++

require 'iudex-da'
require 'iudex-da/key_helper'
require 'iudex-da/pool_data_source_factory'

require 'iudex-httpclient-3'

require 'iudex-worker'
require 'iudex-worker/filter_chain_factory'

require 'hooker'

module Iudex
  module Worker

    class Agent
      include Iudex::DA
      include Iudex::Filter::KeyHelper
      include Iudex::Core
      include Iudex::Worker
      include Gravitext::HTMap

      def initialize
        Hooker.apply( [:iudex,:worker], self )
      end

      def poll_keys
        [ :url, :type, :priority, :next_visit_after, :last_visit, :etag ]
      end

      def run
        Hooker.with( :iudex ) do
          dsf = PoolDataSourceFactory.new
          data_source = dsf.create

          cmapper = ContentMapper.new( keys( poll_keys ) )
          wpoller = WorkPoller.new( data_source, cmapper )
          Hooker.apply( :work_poller, wpoller )

          mgr = HTTPClient3.create_manager
          mgr.start
          http_client = HTTPClient3::HTTPClient3.new( mgr.client )

          fcf = FilterChainFactory.new( 'agent' )
          fcf.http_client = http_client
          fcf.data_source = data_source
          Hooker.apply( :filter_factory, fcf )

          fcf.filter do |chain|
            vexec = VisitExecutor.new( chain, wpoller )
            Hooker.apply( :visit_executor, vexec )

            Hooker.log_not_applied # All hooks should be used by now

            vexec.start
            vexec.join    #Run until interrupted
          end # fcf closes

          mgr.shutdown
          dsf.close
        end
      end

    end

  end
end
