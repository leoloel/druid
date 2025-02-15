/*
 * Copyright 1999-2017 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.sql.dialect.oracle.parser;

import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.oracle.ast.clause.*;
import com.alibaba.druid.sql.dialect.oracle.ast.clause.ModelClause.*;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.*;
import com.alibaba.druid.sql.parser.*;
import com.alibaba.druid.util.FnvHash;

import java.util.List;

public class OracleSelectParser extends SQLSelectParser {
    public OracleSelectParser(String sql) {
        super(new OracleExprParser(sql));
    }

    public OracleSelectParser(SQLExprParser exprParser) {
        super(exprParser);
    }

    public OracleSelectParser(SQLExprParser exprParser, SQLSelectListCache selectListCache) {
        super(exprParser, selectListCache);
    }

    public SQLSelect select() {
        SQLSelect select = new SQLSelect();

        if (lexer.token() == Token.WITH) {
            SQLWithSubqueryClause with = this.parseWith();
            select.setWithSubQuery(with);
        }

        SQLSelectQuery query = query();
        select.setQuery(query);

        SQLOrderBy orderBy = this.parseOrderBy();

        OracleSelectQueryBlock queryBlock = null;
        if (query instanceof SQLSelectQueryBlock) {
            queryBlock = (OracleSelectQueryBlock) query;

            if (queryBlock.getOrderBy() == null) {
                queryBlock.setOrderBy(orderBy);
            } else {
                select.setOrderBy(orderBy);
            }

            if (orderBy != null) {
                parseFetchClause(queryBlock);

                select.setQuery(
                        this.queryRest(queryBlock, true));
            }
        } else {
            select.setOrderBy(orderBy);
        }

        if (lexer.token() == (Token.FOR)) {
            if (queryBlock == null) {
                throw new ParserException("TODO. " + lexer.info());
            }

            lexer.nextToken();
            accept(Token.UPDATE);

            queryBlock.setForUpdate(true);

            // OracleSelectForUpdate forUpdate = new OracleSelectForUpdate();

            if (lexer.token() == Token.OF) {
                lexer.nextToken();
                this.exprParser.exprList(queryBlock.getForUpdateOf(), queryBlock);
            } else if (lexer.token() == Token.LPAREN
                    && queryBlock.isForUpdate()) {
                this.exprParser.exprList(queryBlock.getForUpdateOf(), queryBlock);
            }

            if (lexer.token() == Token.NOWAIT) {
                lexer.nextToken();
                queryBlock.setNoWait(true);
            } else if (lexer.token() == Token.WAIT) {
                lexer.nextToken();
                queryBlock.setWaitTime(this.exprParser.primary());
            } else if (lexer.identifierEquals("SKIP")) {
                lexer.nextToken();
                acceptIdentifier("LOCKED");
                queryBlock.setSkipLocked(true);
            }
        }

        if (lexer.token() == Token.ORDER) {
            orderBy = this.exprParser.parseOrderBy();
            if (queryBlock != null && queryBlock.getOrderBy() == null) {
                queryBlock.setOrderBy(orderBy);
            } else if (select.getOrderBy() == null) {
                select.setOrderBy(orderBy);
            } else {
                throw new ParserException("illegal state.");
            }
        }

        if (lexer.token() == Token.WITH) {
            lexer.nextToken();

            OracleSelectRestriction restriction = null;
            if (lexer.identifierEquals("READ")) {
                lexer.nextToken();

                if (lexer.identifierEquals("ONLY")) {
                    lexer.nextToken();
                } else {
                    throw new ParserException("syntax error. " + lexer.info());
                }

                restriction = new OracleSelectRestriction.ReadOnly();
            } else if (lexer.token() == (Token.CHECK)) {
                lexer.nextToken();

                if (lexer.identifierEquals("OPTION")) {
                    lexer.nextToken();
                } else {
                    throw new ParserException("syntax error. " + lexer.info());
                }

                restriction = new OracleSelectRestriction.CheckOption();
            } else {
                throw new ParserException("syntax error. " + lexer.info());
            }

            if (lexer.token() == Token.CONSTRAINT) {
                lexer.nextToken();
                String constraintName = lexer.stringVal();
                SQLName constraint = new SQLIdentifierExpr(constraintName);
                restriction.setConstraint(constraint);

                lexer.nextToken();
            }

            select.setRestriction(restriction);
        }

        return select;
    }

    @Override
    public SQLWithSubqueryClause parseWith() {
        accept(Token.WITH);
        SQLWithSubqueryClause subqueryFactoringClause = new SQLWithSubqueryClause();
        for (; ; ) {
            OracleWithSubqueryEntry entry = new OracleWithSubqueryEntry();

            String alias = lexer.stringVal();
            lexer.nextToken();
            entry.setAlias(alias);

            if (lexer.token() == Token.LPAREN) {
                lexer.nextToken();
                exprParser.names(entry.getColumns());
                accept(Token.RPAREN);
            }

            accept(Token.AS);
            accept(Token.LPAREN);
            entry.setSubQuery(select());
            accept(Token.RPAREN);

            if (lexer.identifierEquals("SEARCH")) {
                lexer.nextToken();
                SearchClause searchClause = new SearchClause();

                if (lexer.token() != Token.IDENTIFIER) {
                    throw new ParserException("syntax erorr : " + lexer.token());
                }

                if (lexer.identifierEquals(FnvHash.Constants.DEPTH)) {
                    lexer.nextToken();
                    searchClause.setType(SearchClause.Type.DEPTH);
                } else if (lexer.identifierEquals(FnvHash.Constants.BREADTH)) {
                    lexer.nextToken();
                    searchClause.setType(SearchClause.Type.BREADTH);
                } else {
                    searchClause.setType(SearchClause.Type.valueOf(lexer.stringVal().toUpperCase()));
                    lexer.nextToken();
                }

                acceptIdentifier("FIRST");
                accept(Token.BY);

                searchClause.addItem(exprParser.parseSelectOrderByItem());

                while (lexer.token() == (Token.COMMA)) {
                    lexer.nextToken();
                    searchClause.addItem(exprParser.parseSelectOrderByItem());
                }

                accept(Token.SET);

                searchClause.setOrderingColumn((SQLIdentifierExpr) exprParser.name());

                entry.setSearchClause(searchClause);
            }

            if (lexer.identifierEquals("CYCLE")) {
                lexer.nextToken();
                CycleClause cycleClause = new CycleClause();
                exprParser.exprList(cycleClause.getAliases(), cycleClause);
                accept(Token.SET);
                cycleClause.setMark(exprParser.expr());
                accept(Token.TO);
                cycleClause.setValue(exprParser.expr());
                accept(Token.DEFAULT);
                cycleClause.setDefaultValue(exprParser.expr());
                entry.setCycleClause(cycleClause);
            }

            subqueryFactoringClause.addEntry(entry);

            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                continue;
            }

            break;
        }

        return subqueryFactoringClause;
    }

    public SQLSelectQuery query(SQLObject parent, boolean acceptUnion) {
        if (lexer.token() == Token.LPAREN) {
            lexer.nextToken();

            SQLSelectQuery select = query();
            accept(Token.RPAREN);

            return queryRest(select, acceptUnion);
        }

        OracleSelectQueryBlock queryBlock = new OracleSelectQueryBlock();
        if (lexer.hasComment() && lexer.isKeepComments()) {
            queryBlock.addBeforeComment(lexer.readAndResetComments());
        }

        if (lexer.token() == Token.SELECT) {
            lexer.nextToken();

            if (lexer.token() == Token.COMMENT) {
                lexer.nextToken();
            }

            parseHints(queryBlock);

            if (lexer.token() == Token.DISTINCT) {
                queryBlock.setDistionOption(SQLSetQuantifier.DISTINCT);
                lexer.nextToken();
            } else if (lexer.token() == Token.UNIQUE) {
                queryBlock.setDistionOption(SQLSetQuantifier.UNIQUE);
                lexer.nextToken();
            } else if (lexer.token() == Token.ALL) {
                queryBlock.setDistionOption(SQLSetQuantifier.ALL);
                lexer.nextToken();
            }

            this.exprParser.parseHints(queryBlock.getHints());

            parseSelectList(queryBlock);
        }

        parseInto(queryBlock);

        parseFrom(queryBlock);

        parseWhere(queryBlock);

        parseHierachical(queryBlock);

        parseGroupBy(queryBlock);

        // connect by /  start 语法可能在group by之后，因此再次调用此函数
        parseHierachical(queryBlock);

        parseModelClause(queryBlock);

        parseFetchClause(queryBlock);

        return queryRest(queryBlock, acceptUnion);
    }

    public SQLSelectQuery queryRest(SQLSelectQuery selectQuery, boolean acceptUnion) {
        if (!acceptUnion) {
            return selectQuery;
        }

        if (lexer.token() == Token.UNION) {
            do {
                SQLUnionQuery union = new SQLUnionQuery();
                union.setLeft(selectQuery);

                lexer.nextToken();

                if (lexer.token() == Token.ALL) {
                    union.setOperator(SQLUnionOperator.UNION_ALL);
                    lexer.nextToken();
                } else if (lexer.token() == Token.DISTINCT) {
                    union.setOperator(SQLUnionOperator.DISTINCT);
                    lexer.nextToken();
                }

                SQLSelectQuery right = query(null, false);

                union.setRight(right);

                selectQuery = union;

            } while (lexer.token() == Token.UNION);

            selectQuery = queryRest(selectQuery, true);

            return selectQuery;
        }

        if (lexer.token() == Token.INTERSECT) {
            lexer.nextToken();

            SQLUnionQuery union = new SQLUnionQuery();
            union.setLeft(selectQuery);

            union.setOperator(SQLUnionOperator.INTERSECT);

            SQLSelectQuery right = this.query(null, false);
            union.setRight(right);

            return queryRest(union, true);
        }

        if (lexer.token() == Token.MINUS) {
            lexer.nextToken();

            SQLUnionQuery union = new SQLUnionQuery();
            union.setLeft(selectQuery);

            union.setOperator(SQLUnionOperator.MINUS);

            SQLSelectQuery right = this.query(null, false);
            union.setRight(right);

            return queryRest(union, true);
        }

        return selectQuery;
    }

    private void parseModelClause(OracleSelectQueryBlock queryBlock) {
        Lexer.SavePoint savePoint = lexer.mark();

        if (!lexer.identifierEquals(FnvHash.Constants.MODEL)) {
            return;
        }

        lexer.nextToken();

        ModelClause model = new ModelClause();
        parseCellReferenceOptions(model.getCellReferenceOptions());

        if (lexer.identifierEquals(FnvHash.Constants.RETURN)) {
            lexer.nextToken();
            ReturnRowsClause returnRowsClause = new ReturnRowsClause();
            if (lexer.token() == Token.ALL) {
                lexer.nextToken();
                returnRowsClause.setAll(true);
            } else {
                acceptIdentifier("UPDATED");
            }
            acceptIdentifier("ROWS");

            model.setReturnRowsClause(returnRowsClause);
        }

        while (lexer.identifierEquals(FnvHash.Constants.REFERENCE)) {
            ReferenceModelClause referenceModelClause = new ReferenceModelClause();
            lexer.nextToken();

            SQLExpr name = expr();
            referenceModelClause.setName(name);

            accept(Token.ON);
            accept(Token.LPAREN);
            SQLSelect subQuery = this.select();
            accept(Token.RPAREN);
            referenceModelClause.setSubQuery(subQuery);

            parseModelColumnClause(referenceModelClause);

            parseCellReferenceOptions(referenceModelClause.getCellReferenceOptions());

            model.getReferenceModelClauses().add(referenceModelClause);
        }

        parseMainModelClause(model);

        queryBlock.setModelClause(model);
    }

    private void parseMainModelClause(ModelClause modelClause) {
        MainModelClause mainModel = new MainModelClause();

        if (lexer.identifierEquals("MAIN")) {
            lexer.nextToken();
            mainModel.setMainModelName(expr());
        }

        ModelColumnClause modelColumnClause = new ModelColumnClause();
        parseQueryPartitionClause(modelColumnClause);
        mainModel.setModelColumnClause(modelColumnClause);

        acceptIdentifier("DIMENSION");
        accept(Token.BY);
        accept(Token.LPAREN);
        for (; ; ) {
            if (lexer.token() == Token.RPAREN) {
                lexer.nextToken();
                break;
            }

            ModelColumn column = new ModelColumn();
            column.setExpr(expr());
            column.setAlias(as());
            modelColumnClause.getDimensionByColumns().add(column);

            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                continue;
            }
        }

        acceptIdentifier("MEASURES");
        accept(Token.LPAREN);
        for (; ; ) {
            if (lexer.token() == Token.RPAREN) {
                lexer.nextToken();
                break;
            }

            ModelColumn column = new ModelColumn();
            column.setExpr(expr());
            column.setAlias(as());
            modelColumnClause.getMeasuresColumns().add(column);

            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                continue;
            }
        }
        mainModel.setModelColumnClause(modelColumnClause);

        parseCellReferenceOptions(mainModel.getCellReferenceOptions());

        parseModelRulesClause(mainModel);

        modelClause.setMainModel(mainModel);
    }

    private void parseModelRulesClause(MainModelClause mainModel) {
        ModelRulesClause modelRulesClause = new ModelRulesClause();
        if (lexer.identifierEquals("RULES")) {
            lexer.nextToken();
            if (lexer.token() == Token.UPDATE) {
                modelRulesClause.getOptions().add(ModelRuleOption.UPDATE);
                lexer.nextToken();
            } else if (lexer.identifierEquals("UPSERT")) {
                modelRulesClause.getOptions().add(ModelRuleOption.UPSERT);
                lexer.nextToken();
            }

            if (lexer.identifierEquals("AUTOMATIC")) {
                lexer.nextToken();
                accept(Token.ORDER);
                modelRulesClause.getOptions().add(ModelRuleOption.AUTOMATIC_ORDER);
            } else if (lexer.identifierEquals("SEQUENTIAL")) {
                lexer.nextToken();
                accept(Token.ORDER);
                modelRulesClause.getOptions().add(ModelRuleOption.SEQUENTIAL_ORDER);
            }
        }

        if (lexer.identifierEquals("ITERATE")) {
            lexer.nextToken();
            accept(Token.LPAREN);
            modelRulesClause.setIterate(expr());
            accept(Token.RPAREN);

            if (lexer.identifierEquals("UNTIL")) {
                lexer.nextToken();
                accept(Token.LPAREN);
                modelRulesClause.setUntil(expr());
                accept(Token.RPAREN);
            }
        }

        accept(Token.LPAREN);
        for (; ; ) {
            if (lexer.token() == Token.RPAREN) {
                lexer.nextToken();
                break;
            }

            CellAssignmentItem item = new CellAssignmentItem();
            if (lexer.token() == Token.UPDATE) {
                item.setOption(ModelRuleOption.UPDATE);
            } else if (lexer.identifierEquals("UPSERT")) {
                item.setOption(ModelRuleOption.UPSERT);
            }

            item.setCellAssignment(parseCellAssignment());
            item.setOrderBy(this.parseOrderBy());
            accept(Token.EQ);

            SQLExpr expr = this.expr();
            item.setExpr(expr);

            modelRulesClause.getCellAssignmentItems().add(item);

            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                continue;
            }
        }

        mainModel.setModelRulesClause(modelRulesClause);
    }

    private CellAssignment parseCellAssignment() {
        CellAssignment cellAssignment = new CellAssignment();

        cellAssignment.setMeasureColumn(this.exprParser.name());
        accept(Token.LBRACKET);
        this.exprParser.exprList(cellAssignment.getConditions(), cellAssignment);
        accept(Token.RBRACKET);

        return cellAssignment;
    }

    private void parseQueryPartitionClause(ModelColumnClause modelColumnClause) {
        if (lexer.token() == Token.PARTITION) {
            QueryPartitionClause queryPartitionClause = new QueryPartitionClause();

            lexer.nextToken();
            accept(Token.BY);
            if (lexer.token() == Token.LPAREN) {
                lexer.nextToken();
                exprParser.exprList(queryPartitionClause.getExprList(), queryPartitionClause);
                accept(Token.RPAREN);
            } else {
                exprParser.exprList(queryPartitionClause.getExprList(), queryPartitionClause);
            }
            modelColumnClause.setQueryPartitionClause(queryPartitionClause);
        }
    }

    private void parseModelColumnClause(ReferenceModelClause referenceModelClause) {
        throw new ParserException();
    }

    private void parseCellReferenceOptions(List<CellReferenceOption> options) {
        if (lexer.identifierEquals(FnvHash.Constants.IGNORE)) {
            lexer.nextToken();
            acceptIdentifier("NAV");
            options.add(CellReferenceOption.IgnoreNav);
        } else if (lexer.identifierEquals(FnvHash.Constants.KEEP)) {
            lexer.nextToken();
            acceptIdentifier("NAV");
            options.add(CellReferenceOption.KeepNav);
        }

        if (lexer.token() == Token.UNIQUE) {
            lexer.nextToken();
            if (lexer.identifierEquals("DIMENSION")) {
                lexer.nextToken();
                options.add(CellReferenceOption.UniqueDimension);
            } else {
                acceptIdentifier("SINGLE");
                acceptIdentifier("REFERENCE");
                options.add(CellReferenceOption.UniqueDimension);
            }
        }
    }

    @Override
    public SQLTableSource parseTableSource() {
        SQLTableSource tableSource = parseTableSourcePrimary();

        if (tableSource instanceof OracleSelectTableSource) {
            return parseTableSourceRest((OracleSelectTableSource) tableSource);
        }

        return parseTableSourceRest(tableSource);
    }

    public SQLTableSource parseTableSourcePrimary() {
        if (lexer.token() == Token.LPAREN) {
            lexer.nextToken();

            OracleSelectTableSource tableSource;
            if (lexer.token() == Token.SELECT || lexer.token() == Token.WITH) {
                tableSource = new OracleSelectSubqueryTableSource(select());
            } else if (lexer.token() == Token.LPAREN) {
                tableSource = (OracleSelectTableSource) parseTableSource();
            } else if (lexer.token() == Token.IDENTIFIER
                    || lexer.token() == Token.LITERAL_ALIAS) {
                SQLTableSource identTable = parseTableSource();
                accept(Token.RPAREN);
                parsePivot(identTable);
                return identTable;
            } else {
                throw new ParserException("TODO :" + lexer.info());
            }

            accept(Token.RPAREN);

            if ((lexer.token() == Token.UNION || lexer.token() == Token.MINUS || lexer.token() == Token.EXCEPT)
                    && tableSource instanceof OracleSelectSubqueryTableSource) {
                OracleSelectSubqueryTableSource selectSubqueryTableSource = (OracleSelectSubqueryTableSource) tableSource;
                SQLSelect select = selectSubqueryTableSource.getSelect();
                SQLSelectQuery selectQuery = this.queryRest(select.getQuery(), true);
                select.setQuery(selectQuery);
            }

            parsePivot(tableSource);

            return tableSource;
        }

        if (lexer.token() == Token.SELECT) {
            throw new ParserException("TODO. " + lexer.info());
        }

        OracleSelectTableReference tableReference = new OracleSelectTableReference();

        if (lexer.identifierEquals("ONLY")) {
            lexer.nextToken();
            tableReference.setOnly(true);
            accept(Token.LPAREN);
            parseTableSourceQueryTableExpr(tableReference);
            accept(Token.RPAREN);
        } else {
            parseTableSourceQueryTableExpr(tableReference);
            parsePivot(tableReference);
        }

        return tableReference;
    }

    private void parseTableSourceQueryTableExpr(OracleSelectTableReference tableReference) {
        tableReference.setExpr(this.exprParser.expr());

//        {
//            FlashbackQueryClause clause = flashback();
//            tableReference.setFlashback(clause);
//        }

        if (lexer.identifierEquals("SAMPLE")) {
            lexer.nextToken();

            SampleClause sample = new SampleClause();

            if (lexer.identifierEquals("BLOCK")) {
                sample.setBlock(true);
                lexer.nextToken();
            }

            accept(Token.LPAREN);
            this.exprParser.exprList(sample.getPercent(), sample);
            accept(Token.RPAREN);

            if (lexer.identifierEquals("SEED")) {
                lexer.nextToken();
                accept(Token.LPAREN);
                sample.setSeedValue(expr());
                accept(Token.RPAREN);
            }

            tableReference.setSampleClause(sample);
        }

        if (lexer.token() == Token.PARTITION) {
            lexer.nextToken();
            PartitionExtensionClause partition = new PartitionExtensionClause();

            if (lexer.token() == Token.LPAREN) {
                lexer.nextToken();
                partition.setPartition(exprParser.name());
                accept(Token.RPAREN);
            } else if (lexer.token() == Token.BY) {
                lexer.nextToken();
                accept(Token.LPAREN);
                partition.setPartition(exprParser.name());
                accept(Token.RPAREN);
            } else {
                accept(Token.FOR);
                accept(Token.LPAREN);
                exprParser.names(partition.getFor());
                accept(Token.RPAREN);
            }

            tableReference.setPartition(partition);
        }

        if (lexer.identifierEquals("SUBPARTITION")) {
            lexer.nextToken();
            PartitionExtensionClause partition = new PartitionExtensionClause();
            partition.setSubPartition(true);

            if (lexer.token() == Token.LPAREN) {
                lexer.nextToken();
                partition.setPartition(exprParser.name());
                accept(Token.RPAREN);
            } else {
                accept(Token.FOR);
                accept(Token.LPAREN);
                exprParser.names(partition.getFor());
                accept(Token.RPAREN);
            }

            tableReference.setPartition(partition);
        }

        if (lexer.identifierEquals("VERSIONS")) {
            SQLBetweenExpr betweenExpr = new SQLBetweenExpr();
            betweenExpr.setTestExpr(new SQLIdentifierExpr("VERSIONS"));
            lexer.nextToken();

            accept(Token.BETWEEN);

            SQLFlashbackExpr start = new SQLFlashbackExpr();
            if (lexer.identifierEquals("SCN")) {
                lexer.nextToken();
                start.setType(SQLFlashbackExpr.Type.SCN);
            } else {
                acceptIdentifier("TIMESTAMP");
                start.setType(SQLFlashbackExpr.Type.TIMESTAMP);
            }

            SQLBinaryOpExpr binaryExpr = (SQLBinaryOpExpr) exprParser.expr();
            if (binaryExpr.getOperator() != SQLBinaryOperator.BooleanAnd) {
                throw new ParserException("syntax error : " + binaryExpr.getOperator() + ", " + lexer.info());
            }

            start.setExpr(binaryExpr.getLeft());

            betweenExpr.setBeginExpr(start);
            betweenExpr.setEndExpr(binaryExpr.getRight());

            tableReference.setFlashback(betweenExpr);
        }

    }

    private SQLExpr flashback() {
        accept(Token.OF);
        if (lexer.identifierEquals("SCN")) {
            lexer.nextToken();
            return new SQLFlashbackExpr(SQLFlashbackExpr.Type.SCN, this.expr());
        } else if (lexer.identifierEquals("SNAPSHOT")) {
            return this.expr();
        } else {
            lexer.nextToken();
            return new SQLFlashbackExpr(SQLFlashbackExpr.Type.TIMESTAMP, this.expr());
        }
    }

    protected SQLTableSource primaryTableSourceRest(SQLTableSource tableSource) {
        if (tableSource instanceof OracleSelectTableSource) {
            if (lexer.token() == Token.AS) {
                lexer.nextToken();

                if (lexer.token() == Token.OF) {
                    ((OracleSelectTableSource) tableSource).setFlashback(flashback());
                }

                tableSource.setAlias(tableAlias());
            }
        }

        return tableSource;
    }

    protected SQLTableSource parseTableSourceRest(OracleSelectTableSource tableSource) {
        if (lexer.token() == Token.AS) {
            lexer.nextToken();

            if (lexer.token() == Token.OF) {
                tableSource.setFlashback(flashback());
                return parseTableSourceRest(tableSource);
            }

            tableSource.setAlias(tableAlias(true));
        } else if ((tableSource.getAlias() == null) || (tableSource.getAlias().length() == 0)) {
            if (lexer.token() != Token.LEFT && lexer.token() != Token.RIGHT && lexer.token() != Token.FULL) {
                final String tableAlias = tableAlias();
                tableSource.setAlias(tableAlias);
            }
        }

        if (lexer.token() == Token.HINT) {
            this.exprParser.parseHints(tableSource.getHints());
        }

        SQLJoinTableSource.JoinType joinType = null;

        if (lexer.token() == Token.LEFT) {
            lexer.nextToken();
            if (lexer.token() == Token.OUTER) {
                lexer.nextToken();
            }
            accept(Token.JOIN);
            joinType = SQLJoinTableSource.JoinType.LEFT_OUTER_JOIN;
        }

        if (lexer.token() == Token.RIGHT) {
            lexer.nextToken();
            if (lexer.token() == Token.OUTER) {
                lexer.nextToken();
            }
            accept(Token.JOIN);
            joinType = SQLJoinTableSource.JoinType.RIGHT_OUTER_JOIN;
        }

        if (lexer.token() == Token.FULL) {
            lexer.nextToken();
            if (lexer.token() == Token.OUTER) {
                lexer.nextToken();
            }
            accept(Token.JOIN);
            joinType = SQLJoinTableSource.JoinType.FULL_OUTER_JOIN;
        }

        boolean natural = lexer.identifierEquals(FnvHash.Constants.NATURAL);
        if (natural) {
            lexer.nextToken();
        }

        if (lexer.token() == Token.INNER) {
            lexer.nextToken();
            accept(Token.JOIN);
            if (natural) {
                joinType = SQLJoinTableSource.JoinType.NATURAL_INNER_JOIN;
            } else {
                joinType = SQLJoinTableSource.JoinType.INNER_JOIN;
            }
        }
        if (lexer.token() == Token.CROSS) {
            lexer.nextToken();
            accept(Token.JOIN);
            joinType = SQLJoinTableSource.JoinType.CROSS_JOIN;
        }

        if (lexer.token() == Token.JOIN) {
            lexer.nextToken();
            if (natural) {
                joinType = SQLJoinTableSource.JoinType.NATURAL_JOIN;
            } else {
                joinType = SQLJoinTableSource.JoinType.JOIN;
            }
        }

        if (lexer.token() == (Token.COMMA)) {
            lexer.nextToken();
            joinType = SQLJoinTableSource.JoinType.COMMA;
        }

        if (joinType != null) {
            OracleSelectJoin join = new OracleSelectJoin();
            join.setLeft(tableSource);
            join.setJoinType(joinType);

            SQLTableSource right;
            right = parseTableSourcePrimary();
            String tableAlias = tableAlias();
            right.setAlias(tableAlias);
            join.setRight(right);

            if (lexer.token() == Token.ON) {
                lexer.nextToken();
                join.setCondition(this.exprParser.expr());

                if (lexer.token() == Token.ON
                        && tableSource instanceof SQLJoinTableSource
                        && ((SQLJoinTableSource) tableSource).getCondition() == null) {
                    lexer.nextToken();
                    SQLExpr leftCondidition = this.exprParser.expr();
                    ((SQLJoinTableSource) tableSource).setCondition(leftCondidition);
                }
            } else if (lexer.token() == Token.USING) {
                lexer.nextToken();
                accept(Token.LPAREN);
                this.exprParser.exprList(join.getUsing(), join);
                accept(Token.RPAREN);
            }

            parsePivot(join);

            return parseTableSourceRest(join);
        } else {
            if (lexer.identifierEquals(FnvHash.Constants.PIVOT)) {
                parsePivot(tableSource);
            }
        }

        return tableSource;
    }

    protected void parseInto(OracleSelectQueryBlock x) {
        if (lexer.token() == Token.INTO) {
            lexer.nextToken();

            if (lexer.token() == Token.FROM) {
                return;
            }

            SQLExpr expr = expr();
            if (lexer.token() != Token.COMMA) {
                x.setInto(expr);
                return;
            }
            SQLListExpr list = new SQLListExpr();
            list.addItem(expr);
            while (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                list.addItem(expr());
            }
            x.setInto(list);
        }
    }

    private void parseHints(OracleSelectQueryBlock queryBlock) {
        this.exprParser.parseHints(queryBlock.getHints());
    }
}
