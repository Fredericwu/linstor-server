package com.linbit.linstor.core.objects;

import com.linbit.ImplementationError;
import com.linbit.linstor.LinStorDataAlreadyExistsException;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.dbdrivers.interfaces.NodeConnectionDatabaseDriver;
import com.linbit.linstor.propscon.PropsContainerFactory;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.security.AccessType;
import com.linbit.linstor.transaction.TransactionObjectFactory;
import com.linbit.linstor.transaction.manager.TransactionMgr;

import javax.inject.Inject;
import javax.inject.Provider;
import java.util.UUID;

public class NodeConnectionFactory
{
    private final NodeConnectionDatabaseDriver dbDriver;
    private final PropsContainerFactory propsContainerFactory;
    private final TransactionObjectFactory transObjFactory;
    private final Provider<TransactionMgr> transMgrProvider;

    @Inject
    public NodeConnectionFactory(
        NodeConnectionDatabaseDriver dbDriverRef,
        PropsContainerFactory propsContainerFactoryRef,
        TransactionObjectFactory transObjFactoryRef,
        Provider<TransactionMgr> transMgrProviderRef
    )
    {
        dbDriver = dbDriverRef;
        propsContainerFactory = propsContainerFactoryRef;
        transObjFactory = transObjFactoryRef;
        transMgrProvider = transMgrProviderRef;
    }

    public NodeConnection create(
        AccessContext accCtx,
        Node node1,
        Node node2
    )
        throws AccessDeniedException, DatabaseException, LinStorDataAlreadyExistsException
    {
        node1.getObjProt().requireAccess(accCtx, AccessType.CHANGE);
        node2.getObjProt().requireAccess(accCtx, AccessType.CHANGE);

        NodeConnection nodeConData = NodeConnection.get(accCtx, node1, node2);

        if (nodeConData != null)
        {
            throw new LinStorDataAlreadyExistsException("The NodeConnection already exists");
        }

        nodeConData = new NodeConnection(
            UUID.randomUUID(),
            node1,
            node2,
            dbDriver,
            propsContainerFactory,
            transObjFactory,
            transMgrProvider
        );
        dbDriver.create(nodeConData);

        node1.setNodeConnection(accCtx, nodeConData);
        node2.setNodeConnection(accCtx, nodeConData);

        return nodeConData;
    }

    public NodeConnection getInstanceSatellite(
        AccessContext accCtx,
        UUID uuid,
        Node node1,
        Node node2
    )
        throws ImplementationError
    {
        NodeConnection nodeConData = null;

        Node source;
        Node target;
        if (node1.getName().compareTo(node2.getName()) < 0)
        {
            source = node1;
            target = node2;
        }
        else
        {
            source = node2;
            target = node1;
        }

        try
        {
            nodeConData = (NodeConnection) source.getNodeConnection(accCtx, target);
            if (nodeConData == null)
            {
                nodeConData = new NodeConnection(
                    uuid,
                    source,
                    target,
                    dbDriver,
                    propsContainerFactory,
                    transObjFactory,
                    transMgrProvider
                );

                source.setNodeConnection(accCtx, nodeConData);
                target.setNodeConnection(accCtx, nodeConData);
            }
        }
        catch (Exception exc)
        {
            throw new ImplementationError(
                "This method should only be called with a satellite db in background!",
                exc
            );
        }
        return nodeConData;
    }
}
