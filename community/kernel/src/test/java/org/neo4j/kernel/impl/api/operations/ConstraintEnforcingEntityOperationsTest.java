/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.operations;

import org.junit.Before;
import org.junit.Test;

import org.neo4j.kernel.api.index.InternalIndexState;
import org.neo4j.kernel.api.schema.IndexQuery;
import org.neo4j.kernel.api.schema.index.IndexDescriptor;
import org.neo4j.kernel.api.schema.index.IndexDescriptorFactory;
import org.neo4j.kernel.impl.api.ConstraintEnforcingEntityOperations;
import org.neo4j.kernel.impl.api.KernelStatement;
import org.neo4j.kernel.impl.constraints.StandardConstraintSemantics;
import org.neo4j.kernel.impl.locking.LockTracer;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.locking.SimpleStatementLocks;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.neo4j.kernel.api.StatementConstants.NO_SUCH_NODE;
import static org.neo4j.kernel.api.schema.IndexQuery.exact;
import static org.neo4j.kernel.impl.locking.ResourceTypes.INDEX_ENTRY;
import static org.neo4j.kernel.impl.locking.ResourceTypes.indexEntryResourceId;

public class ConstraintEnforcingEntityOperationsTest
{
    private final int labelId = 1;
    private final int propertyKeyId = 2;
    private final String value = "value";
    private final long resourceId = indexEntryResourceId( labelId, exact( propertyKeyId, value ) );
    private final IndexDescriptor index = IndexDescriptorFactory.uniqueForLabel( labelId, propertyKeyId );
    private final IndexQuery.ExactPredicate withValue = IndexQuery.exact( propertyKeyId, value );
    private EntityReadOperations readOps;
    private KernelStatement state;
    private Locks.Client locks;
    private ConstraintEnforcingEntityOperations ops;

    @Before
    public void given_ConstraintEnforcingEntityOperations_with_OnlineIndex() throws Exception
    {
        this.readOps = mock( EntityReadOperations.class );
        SchemaReadOperations schemaReadOps = mock( SchemaReadOperations.class );
        SchemaWriteOperations schemaWriteOps = mock( SchemaWriteOperations.class );
        this.state = mock( KernelStatement.class );
        when( schemaReadOps.indexGetState( state, index ) ).thenReturn( InternalIndexState.ONLINE );
        this.locks = mock( Locks.Client.class );
        when( state.locks() ).thenReturn( new SimpleStatementLocks( locks ) );
        when( state.lockTracer() ).thenReturn( LockTracer.NONE );

        this.ops = new ConstraintEnforcingEntityOperations( new StandardConstraintSemantics(), null, readOps, schemaWriteOps, schemaReadOps );
    }

    @Test
    public void shouldHoldIndexReadLockIfNodeIsExists() throws Exception
    {
        // given
        long expectedNodeId = 15;
        when( readOps.nodeGetFromUniqueIndexSeek( state, index, withValue ) ).thenReturn( expectedNodeId );

        // when
        long nodeId = ops.nodeGetFromUniqueIndexSeek( state, index, withValue );

        // then
        assertEquals( expectedNodeId, nodeId );
        verify( locks).acquireShared(
                LockTracer.NONE,
                INDEX_ENTRY, resourceId );
        verifyNoMoreInteractions( locks );
    }

    @Test
    public void shouldHoldIndexWriteLockIfNodeDoesNotExist() throws Exception
    {
        // given
        when( readOps.nodeGetFromUniqueIndexSeek( state, index, withValue ) ).thenReturn( NO_SUCH_NODE );

        // when
        long nodeId = ops.nodeGetFromUniqueIndexSeek( state, index, withValue );

        // then
        assertEquals( NO_SUCH_NODE, nodeId );
        verify( locks ).acquireShared(
                LockTracer.NONE,
                INDEX_ENTRY, resourceId );
        verify( locks ).acquireExclusive(
                LockTracer.NONE,
                INDEX_ENTRY, resourceId );
        verify( locks ).releaseShared( INDEX_ENTRY, resourceId );
        verifyNoMoreInteractions( locks );
    }

    @Test
    public void shouldHoldIndexReadLockIfNodeIsConcurrentlyCreated() throws Exception
    {
        // given
        long expectedNodeId = 15;
        when( readOps.nodeGetFromUniqueIndexSeek( state, index, withValue ) )
                .thenReturn( NO_SUCH_NODE )
                .thenReturn( expectedNodeId );

        // when
        long nodeId = ops.nodeGetFromUniqueIndexSeek( state, index, withValue );

        // then
        assertEquals( expectedNodeId, nodeId );
        verify( locks, times(2) ).acquireShared(
                LockTracer.NONE,
                INDEX_ENTRY, resourceId );
        verify( locks ).acquireExclusive(
                LockTracer.NONE,
                INDEX_ENTRY, resourceId );
        verify( locks ).releaseShared( INDEX_ENTRY, resourceId );
        verify( locks ).releaseExclusive( INDEX_ENTRY, resourceId );
        verifyNoMoreInteractions( locks );
    }
}
