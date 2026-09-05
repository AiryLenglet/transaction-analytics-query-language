-- ---------------------------------------------------------------------
-- The original Transactions table, plus the two dimensions the example
-- queries already assume: example.taql groups by `country` and by
-- `transactionType`, neither of which existed as a column. They are
-- modelled as joins so the catalog has something real to resolve them
-- through.
-- ---------------------------------------------------------------------

IF OBJECT_ID('dbo.Transactions', 'U') IS NOT NULL DROP TABLE dbo.Transactions;
IF OBJECT_ID('dbo.Counterparties', 'U') IS NOT NULL DROP TABLE dbo.Counterparties;
IF OBJECT_ID('dbo.TransactionTypes', 'U') IS NOT NULL DROP TABLE dbo.TransactionTypes;

CREATE TABLE dbo.TransactionTypes (
    [TransactionTypeId] VARCHAR (50) NOT NULL PRIMARY KEY,
    [Name]              VARCHAR (50) NOT NULL
);

CREATE TABLE dbo.Counterparties (
    [CounterpartyId] VARCHAR (50)  NOT NULL PRIMARY KEY,
    [Name]           VARCHAR (200) NOT NULL,
    [Country]        VARCHAR (2)   NOT NULL
);

CREATE TABLE dbo.Transactions (
    [TransactionId]     VARCHAR (50) NOT NULL PRIMARY KEY,
    [ClientId]          VARCHAR (50) NOT NULL,
    [CounterpartyId]    VARCHAR (50) NOT NULL REFERENCES dbo.Counterparties ([CounterpartyId]),
    [TransactionTypeId] VARCHAR (50) NOT NULL REFERENCES dbo.TransactionTypes ([TransactionTypeId]),
    [Currency]          VARCHAR (3)  NOT NULL,
    [TransactionValue]  DECIMAL (10, 2) NOT NULL,
    [TransactionDate]   DATE         NOT NULL,
    [Direction]         VARCHAR (1)  NOT NULL
);

-- The DSL always filters by client and date, so that is the covering index.
CREATE INDEX IX_Transactions_Client_Date
    ON dbo.Transactions ([ClientId], [TransactionDate])
    INCLUDE ([TransactionValue], [Direction], [CounterpartyId], [TransactionTypeId]);

INSERT INTO dbo.TransactionTypes ([TransactionTypeId], [Name]) VALUES
    ('TT1', 'Cash'), ('TT2', 'Cheques'), ('TT3', 'Instrument'), ('TT4', 'Wire');

INSERT INTO dbo.Counterparties ([CounterpartyId], [Name], [Country]) VALUES
    ('CP1', 'Alpha Bank',      'CH'),
    ('CP2', 'Beta Holdings',   'FR'),
    ('CP3', 'Gamma Partners',  'DE'),
    ('CP4', 'Delta Capital',   'CH'),
    ('CP5', 'Epsilon Trading', 'GB');

INSERT INTO dbo.Transactions
    ([TransactionId], [ClientId], [CounterpartyId], [TransactionTypeId], [Currency], [TransactionValue], [TransactionDate], [Direction]) VALUES
    ('T01', '1', 'CP1', 'TT1', 'CHF',  1200.00, '2011-03-14', 'C'),
    ('T02', '1', 'CP2', 'TT1', 'EUR',   840.50, '2012-07-02', 'D'),
    ('T03', '1', 'CP3', 'TT2', 'EUR',  5300.00, '2013-01-20', 'C'),
    ('T04', '1', 'CP1', 'TT3', 'CHF', 12750.25, '2014-11-05', 'C'),
    ('T05', '1', 'CP4', 'TT3', 'CHF',  9900.00, '2015-06-18', 'D'),
    ('T06', '2', 'CP5', 'TT4', 'GBP',  4400.00, '2016-02-29', 'C'),
    ('T07', '3', 'CP2', 'TT1', 'EUR',   275.75, '2016-09-09', 'C'),
    ('T08', '3', 'CP3', 'TT2', 'EUR',  1850.00, '2017-04-23', 'D'),
    ('T09', '3', 'CP1', 'TT1', 'CHF',  6100.00, '2018-08-30', 'C'),
    ('T10', '3', 'CP4', 'TT3', 'CHF', 22300.00, '2019-12-31', 'C'),
    ('T11', '3', 'CP5', 'TT4', 'GBP',  3150.00, '2019-05-15', 'D'),
    ('T12', '4', 'CP1', 'TT1', 'CHF',   980.00, '2021-01-11', 'C');
