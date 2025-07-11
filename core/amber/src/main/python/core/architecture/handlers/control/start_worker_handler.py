# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

from core.models import Schema
from proto.edu.uci.ics.amber.core import (
    ChannelIdentity,
    ActorVirtualIdentity,
    PortIdentity,
    EmbeddedControlMessageIdentity,
)
from proto.edu.uci.ics.amber.engine.architecture.rpc import (
    WorkerStateResponse,
    ControlInvocation,
    EmptyRequest,
    EmbeddedControlMessage,
    AsyncRpcContext,
    ControlRequest,
    EmbeddedControlMessageType,
)
from proto.edu.uci.ics.amber.engine.architecture.worker import (
    WorkerState,
)
from core.architecture.handlers.control.control_handler_base import ControlHandler
from core.architecture.packaging.input_manager import InputManager
from core.models.internal_queue import ECMElement


class StartWorkerHandler(ControlHandler):
    async def start_worker(self, req: EmptyRequest) -> WorkerStateResponse:
        if self.context.executor_manager.executor.is_source:
            self.context.state_manager.transit_to(WorkerState.RUNNING)
            input_channel_id = ChannelIdentity(
                InputManager.SOURCE_STARTER,
                ActorVirtualIdentity(self.context.worker_id),
                False,
            )
            port_id = PortIdentity(0, False)
            self.context.input_manager.add_input_port(
                port_id=port_id, schema=Schema(), storage_uris=[], partitionings=[]
            )
            self.context.input_manager.register_input(input_channel_id, port_id)
            self.context.current_input_channel_id = input_channel_id
            self.context.input_queue.put(
                ECMElement(
                    tag=input_channel_id,
                    payload=EmbeddedControlMessage(
                        EmbeddedControlMessageIdentity("StartChannel"),
                        EmbeddedControlMessageType.NO_ALIGNMENT,
                        [],
                        {
                            input_channel_id.to_worker_id.name: ControlInvocation(
                                "StartChannel",
                                ControlRequest(empty_request=EmptyRequest()),
                                AsyncRpcContext(
                                    ActorVirtualIdentity(), ActorVirtualIdentity()
                                ),
                                -1,
                            )
                        },
                    ),
                )
            )
            self.context.input_queue.put(
                ECMElement(
                    tag=input_channel_id,
                    payload=EmbeddedControlMessage(
                        EmbeddedControlMessageIdentity("EndChannel"),
                        EmbeddedControlMessageType.PORT_ALIGNMENT,
                        [],
                        {
                            input_channel_id.to_worker_id.name: ControlInvocation(
                                "EndChannel",
                                ControlRequest(empty_request=EmptyRequest()),
                                AsyncRpcContext(
                                    ActorVirtualIdentity(), ActorVirtualIdentity()
                                ),
                                -1,
                            )
                        },
                    ),
                )
            )
        elif self.context.input_manager.get_input_port_mat_reader_threads():
            self.context.input_manager.start_input_port_mat_reader_threads()

        return WorkerStateResponse(self.context.state_manager.get_current_state())
