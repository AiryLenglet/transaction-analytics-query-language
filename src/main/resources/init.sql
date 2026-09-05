-- The original Transactions table, with CounterpartyName, Country and
-- TransactionType held directly on the row.
--
-- The DROP is here only because Main re-runs this script on every start. The GO
-- separators matter: without them the whole file is one batch, and the INSERT
-- would be parsed against whatever Transactions looked like before the CREATE.

IF OBJECT_ID('dbo.Transactions', 'U') IS NOT NULL DROP TABLE dbo.Transactions;
GO

CREATE TABLE [Transactions] (
    [TransactionId] VARCHAR (50),
    [ClientId] VARCHAR (50),
    [CounterpartyId] VARCHAR (50),
    [CounterpartyName] VARCHAR (200),
    [Country] VARCHAR (2),
    [TransactionType] VARCHAR (50),
    [Currency] VARCHAR (3),
    [TransactionValue] DECIMAL (10, 2),
    [TransactionDate] DATE,
    [Direction] VARCHAR (1),
);
GO

-- Not part of the original DDL: the DSL always filters by client and date, so
-- this is the index the generated predicates are shaped to seek on. Drop it if
-- you would rather keep the schema exactly as it was.
CREATE INDEX IX_Transactions_Client_Date
    ON [Transactions] ([ClientId], [TransactionDate])
    INCLUDE ([TransactionValue], [Direction], [Country], [TransactionType]);
GO

INSERT INTO [Transactions]
    ([TransactionId], [ClientId], [CounterpartyId], [CounterpartyName], [Country],
     [TransactionType], [Currency], [TransactionValue], [TransactionDate], [Direction]) VALUES
    ('T01', '1', 'CP1', 'Alpha Bank',      'CH', 'Cash',       'CHF',  1200.00, '2011-03-14', 'C'),
    ('T02', '1', 'CP2', 'Beta Holdings',   'FR', 'Cash',       'EUR',   840.50, '2012-07-02', 'D'),
    ('T03', '1', 'CP3', 'Gamma Partners',  'DE', 'Cheques',    'EUR',  5300.00, '2013-01-20', 'C'),
    ('T04', '1', 'CP1', 'Alpha Bank',      'CH', 'Instrument', 'CHF', 12750.25, '2014-11-05', 'C'),
    ('T05', '1', 'CP4', 'Delta Capital',   'CH', 'Instrument', 'CHF',  9900.00, '2015-06-18', 'D'),
    ('T06', '2', 'CP5', 'Epsilon Trading', 'GB', 'Wire',       'GBP',  4400.00, '2016-02-29', 'C'),
    ('T07', '3', 'CP2', 'Beta Holdings',   'FR', 'Cash',       'EUR',   275.75, '2016-09-09', 'C'),
    ('T08', '3', 'CP3', 'Gamma Partners',  'DE', 'Cheques',    'EUR',  1850.00, '2017-04-23', 'D'),
    ('T09', '3', 'CP1', 'Alpha Bank',      'CH', 'Cash',       'CHF',  6100.00, '2018-08-30', 'C'),
    ('T10', '3', 'CP4', 'Delta Capital',   'CH', 'Instrument', 'CHF', 22300.00, '2019-12-31', 'C'),
    ('T11', '3', 'CP5', 'Epsilon Trading', 'GB', 'Wire',       'GBP',  3150.00, '2019-05-15', 'D'),
    ('T12', '4', 'CP1', 'Alpha Bank',      'CH', 'Cash',       'CHF',   980.00, '2021-01-11', 'C');
