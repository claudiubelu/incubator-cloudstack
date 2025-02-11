// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
// 
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.vm.dao;

import java.util.List;

import com.cloud.utils.db.GenericDao;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;

public interface NicDao extends GenericDao<NicVO, Long> {
    List<NicVO> listByVmId(long instanceId);
    
    List<String> listIpAddressInNetwork(long networkConfigId);
    List<NicVO> listByVmIdIncludingRemoved(long instanceId);
    
    List<NicVO> listByNetworkId(long networkId);
    
    NicVO findByInstanceIdAndNetworkId(long networkId, long instanceId);
    
    NicVO findByInstanceIdAndNetworkIdIncludingRemoved(long networkId, long instanceId);
    
    NicVO findByNetworkIdTypeAndGateway(long networkId, VirtualMachine.Type vmType, String gateway);

    void removeNicsForInstance(long instanceId);
    
    NicVO findByNetworkIdAndType(long networkId, VirtualMachine.Type vmType);
    
    NicVO findByIp4AddressAndNetworkId(String ip4Address, long networkId);
    
    NicVO findDefaultNicForVM(long instanceId);

    /**
     * @param networkId
     * @param instanceId
     * @return
     */
    NicVO findNonReleasedByInstanceIdAndNetworkId(long networkId, long instanceId);
    
    String getIpAddress(long networkId, long instanceId);
    
    int countNics(long instanceId);
    
    NicVO findByNetworkIdInstanceIdAndBroadcastUri(long networkId, long instanceId, String broadcastUri);
    
    NicVO findByIp4AddressAndNetworkIdAndInstanceId(long networkId, long instanceId, String ip4Address);

    List<NicVO> listByVmIdAndNicId(Long vmId, Long nicId);
}
