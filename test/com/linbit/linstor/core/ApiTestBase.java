package com.linbit.linstor.core;

import com.google.inject.Inject;
import com.google.inject.testing.fieldbinder.Bind;
import com.google.inject.testing.fieldbinder.BoundFieldModule;
import com.google.inject.util.Modules;
import com.linbit.ServiceName;
import com.linbit.TransactionMgr;
import com.linbit.linstor.NetInterface.NetInterfaceApi;
import com.linbit.linstor.Node;
import com.linbit.linstor.SatelliteConnection.SatelliteConnectionApi;
import com.linbit.linstor.StorPoolDefinition;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.ApiCallRc.RcEntry;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.utils.AbsApiCallTester;
import com.linbit.linstor.api.utils.DummyTcpConnector;
import com.linbit.linstor.api.utils.NetInterfaceApiTestImpl;
import com.linbit.linstor.api.utils.SatelliteConnectionApiTestImpl;
import com.linbit.linstor.dbdrivers.ControllerDbModule;
import com.linbit.linstor.netcom.NetComContainer;
import com.linbit.linstor.netcom.TcpConnector;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.security.AccessType;
import com.linbit.linstor.security.ControllerSecurityModule;
import com.linbit.linstor.security.DerbyBase;
import com.linbit.linstor.security.Identity;
import com.linbit.linstor.security.ObjectProtection;
import com.linbit.linstor.security.Role;
import com.linbit.linstor.security.SecurityType;
import com.linbit.linstor.security.TestAccessContextProvider;
import com.linbit.linstor.testclient.ApiRCUtils;
import org.junit.Assert;
import org.junit.Before;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import javax.inject.Named;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class ApiTestBase extends DerbyBase
{
    protected static final AccessContext ALICE_ACC_CTX;
    protected static final AccessContext BOB_ACC_CTX;
    static
    {
        ALICE_ACC_CTX = TestAccessContextProvider.ALICE_ACC_CTX;
        BOB_ACC_CTX = TestAccessContextProvider.BOB_ACC_CTX;
    }

    @Bind @Mock
    protected SatelliteConnector satelliteConnector;

    @Bind @Mock
    protected NetComContainer netComContainer;

    @Inject @Named(ControllerDbModule.DISKLESS_STOR_POOL_DFN)
    protected StorPoolDefinition disklessStorPoolDfn;

    @Inject @Named(ControllerSecurityModule.NODES_MAP_PROT)
    protected ObjectProtection nodesMapProt;

    @Inject @Named(ControllerSecurityModule.RSC_DFN_MAP_PROT)
    protected ObjectProtection rscDfnMapProt;

    @Inject @Named(ControllerSecurityModule.STOR_POOL_DFN_MAP_PROT)
    protected ObjectProtection storPoolDfnMapProt;

    @Inject @Named(ControllerCoreModule.CONTROLLER_PROPS)
    protected Props ctrlConf;

    @Before
    @Override
    public void setUp() throws Exception
    {
        MockitoAnnotations.initMocks(this);

        super.setUp(Modules.combine(
            new ApiCallHandlerModule(),
            new CtrlApiCallHandlerModule(),
            new ControllerSecurityModule(),
            new ControllerCoreModule(),
            new ConfigModule(),
            BoundFieldModule.of(this)
        ));

        TransactionMgr transMgr = new TransactionMgr(dbConnPool);

        ctrlConf.setConnection(transMgr);
        ctrlConf.setProp(Controller.PROPSCON_KEY_DEFAULT_PLAIN_CON_SVC, "ignore");
        ctrlConf.setProp(Controller.PROPSCON_KEY_DEFAULT_SSL_CON_SVC, "ignore");

        create(transMgr, ALICE_ACC_CTX);
        create(transMgr, BOB_ACC_CTX);

        transMgr.commit();
        dbConnPool.returnConnection(transMgr);

        TcpConnector tcpConnector = new DummyTcpConnector();
        Mockito.when(netComContainer.getNetComConnector(Mockito.any(ServiceName.class))).thenReturn(tcpConnector);
    }

    private void create(TransactionMgr transMgr, AccessContext accCtx) throws AccessDeniedException, SQLException
    {
        Identity.create(SYS_CTX, accCtx.subjectId.name);
        SecurityType.create(SYS_CTX, accCtx.subjectDomain.name);
        Role.create(SYS_CTX, accCtx.subjectRole.name);

        {
            // TODO each line in this block should be called in the corresponding .create method from the lines above
            insertIdentity(transMgr, accCtx.subjectId.name);
            insertSecType(transMgr, accCtx.subjectDomain.name);
            insertRole(transMgr, accCtx.subjectRole.name, accCtx.subjectDomain.name);
        }

        nodesMapProt.getSecurityType().addRule(SYS_CTX, accCtx.subjectDomain, AccessType.CHANGE);
        rscDfnMapProt.getSecurityType().addRule(SYS_CTX, accCtx.subjectDomain, AccessType.CHANGE);
        storPoolDfnMapProt.getSecurityType().addRule(SYS_CTX, accCtx.subjectDomain, AccessType.CHANGE);

        accCtx.subjectDomain.addRule(SYS_CTX, accCtx.subjectDomain, AccessType.CONTROL);

        nodesMapProt.setConnection(transMgr);
        rscDfnMapProt.setConnection(transMgr);
        storPoolDfnMapProt.setConnection(transMgr);
        nodesMapProt.addAclEntry(SYS_CTX, accCtx.subjectRole, AccessType.CHANGE);
        rscDfnMapProt.addAclEntry(SYS_CTX, accCtx.subjectRole, AccessType.CHANGE);
        storPoolDfnMapProt.addAclEntry(SYS_CTX, accCtx.subjectRole, AccessType.CHANGE);

        ObjectProtection disklessStorPoolDfnProt = disklessStorPoolDfn.getObjProt();
        disklessStorPoolDfnProt.setConnection(transMgr);
        disklessStorPoolDfnProt.addAclEntry(SYS_CTX, accCtx.subjectRole, AccessType.CHANGE);
    }

    protected static NetInterfaceApi createNetInterfaceApi(String name, String address)
    {
        return createNetInterfaceApi(java.util.UUID.randomUUID(), name, address);
    }

    protected static NetInterfaceApi createNetInterfaceApi(java.util.UUID uuid, String name, String address)
    {
        return new NetInterfaceApiTestImpl(uuid, name, address);
    }

    protected static SatelliteConnectionApi createStltConnApi(String netIfName)
    {
        return createStltConnApi(netIfName, ApiConsts.DFLT_STLT_PORT_PLAIN, ApiConsts.VAL_NETCOM_TYPE_PLAIN);
    }

    protected static SatelliteConnectionApi createStltConnApi(
        String netIfName,
        Integer port,
        String encryptionType
    )
    {
        return new SatelliteConnectionApiTestImpl(netIfName, port, encryptionType);
    }

    protected void expectRc(long index, long expectedRc, RcEntry rcEntry)
    {
        if (rcEntry.getReturnCode() != expectedRc)
        {
            Assert.fail("Expected [" + index + "] RC to be " +
                resolveRC(expectedRc) + " but got " +
                resolveRC(rcEntry.getReturnCode())
            );
        }
    }

    private String resolveRC(long expectedRc)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        ApiRCUtils.appendReadableRetCode(sb, expectedRc);
        sb.append("]");
        return sb.toString();
    }

    protected RcEntry checkedGet(ApiCallRc rc, int idx)
    {
        assertThat(rc.getEntries().size()).isGreaterThanOrEqualTo(idx + 1);

        return rc.getEntries().get(idx);
    }

    protected RcEntry checkedGet(ApiCallRc rc, int idx, int expectedSize)
    {
        assertThat(expectedSize).isGreaterThan(idx);
        assertThat(rc.getEntries()).hasSize(expectedSize);

        return rc.getEntries().get(idx);
    }

    protected void evaluateTestSequence(AbsApiCallTester... callSequence)
    {
        for (AbsApiCallTester currentCall : callSequence)
        {
            evaluateTest(currentCall);
        }
    }

    protected void evaluateTest(AbsApiCallTester currentCall)
    {
        Mockito.reset(satelliteConnector);

        ApiCallRc rc = currentCall.executeApiCall();

        List<Long> expectedRetCodes = currentCall.retCodes;
        List<RcEntry> actualRetCodes = rc.getEntries();

        assertThat(actualRetCodes).hasSameSizeAs(expectedRetCodes);
        for (int idx = 0; idx < expectedRetCodes.size(); idx++)
        {
            expectRc(idx, expectedRetCodes.get(idx), actualRetCodes.get(idx));
        }

        Mockito.verify(satelliteConnector, Mockito.times(currentCall.expectedConnectingAttempts.size()))
            .connectSatellite(
                Mockito.any(InetSocketAddress.class),
                Mockito.any(TcpConnector.class),
                Mockito.any(Node.class)
            );
    }
}
